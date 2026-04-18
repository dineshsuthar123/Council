# Comprehensive test script for Council application

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Council Application - Comprehensive Test Suite" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

$testsPassed = 0
$testsFailed = 0
$testsSkipped = 0

function Test-Endpoint {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Method = "GET",
        [string]$Body = $null
    )

    Write-Host "Testing: $Name..." -ForegroundColor Yellow -NoNewline

    try {
        $params = @{
            Uri = $Url
            Method = $Method
            UseBasicParsing = $true
            ErrorAction = "Stop"
        }

        if ($Body) {
            $params.Body = $Body
            $params.ContentType = "application/json"
        }

        $response = Invoke-WebRequest @params

        if ($response.StatusCode -eq 200) {
            Write-Host " ✅ PASS" -ForegroundColor Green
            $script:testsPassed++
            return $response.Content | ConvertFrom-Json
        } else {
            Write-Host " ❌ FAIL (Status: $($response.StatusCode))" -ForegroundColor Red
            $script:testsFailed++
            return $null
        }
    } catch {
        Write-Host " ❌ FAIL ($($_.Exception.Message))" -ForegroundColor Red
        $script:testsFailed++
        return $null
    }
}

# Test 1: Health check
Write-Host ""
Write-Host "---------------------------------------------------" -ForegroundColor Gray
Write-Host "1. BASIC CONNECTIVITY" -ForegroundColor Cyan
Write-Host "---------------------------------------------------" -ForegroundColor Gray

$health = Test-Endpoint -Name "Health Endpoint" -Url "http://localhost:8080/api/v1/health"

if ($health) {
    Write-Host "  Status: $($health.status)" -ForegroundColor Gray
    Write-Host "  Routing Enabled: $($health.routingEnabled)" -ForegroundColor Gray
    Write-Host "  Available Providers: $($health.availableProviders.Count)" -ForegroundColor Gray
}

# Test 2: Provider Status
Write-Host ""
Write-Host "---------------------------------------------------" -ForegroundColor Gray
Write-Host "2. PROVIDER CONFIGURATION" -ForegroundColor Cyan
Write-Host "---------------------------------------------------" -ForegroundColor Gray

$providers = Test-Endpoint -Name "Providers Status" -Url "http://localhost:8080/api/v1/providers/status"

if ($providers) {
    Write-Host ""
    Write-Host "Provider Details:" -ForegroundColor Cyan
    foreach ($p in $providers) {
        $status = if ($p.enabled) { "[ENABLED]" } else { "[DISABLED]" }
        $cooldown = if ($p.coolingDown) { "[IN COOLDOWN]" } else { "[AVAILABLE]" }
        Write-Host "  * $($p.provider.PadRight(12)) | Model: $($p.model.PadRight(25)) | $status | $cooldown"
    }

    $enabledCount = ($providers | Where-Object { $_.enabled }).Count
    Write-Host ""
    Write-Host "Summary: $enabledCount/$($providers.Count) providers enabled" -ForegroundColor Yellow
}

# Test 3: Reasoning endpoint
Write-Host ""
Write-Host "---------------------------------------------------" -ForegroundColor Gray
Write-Host "3. REASONING ENGINE TEST" -ForegroundColor Cyan
Write-Host "---------------------------------------------------" -ForegroundColor Gray

$reasonBody = '{"query":"What is 2+2? Answer concisely."}'
$reason = Test-Endpoint -Name "Reasoning Endpoint" -Url "http://localhost:8080/api/v1/reason" -Method "POST" -Body $reasonBody

if ($reason) {
    Write-Host ""
    Write-Host "  Final Answer: $($reason.finalAnswer)" -ForegroundColor Green
    Write-Host "  Confidence: $($reason.confidence)" -ForegroundColor Gray
    Write-Host "  Judge Reason: $($reason.judgeReason)" -ForegroundColor Gray
    Write-Host "  Used Providers: $($reason.usedProviders -join ', ')" -ForegroundColor Gray
    Write-Host "  Failed Providers: $(if ($reason.failedProviders) { $reason.failedProviders -join ', ' } else { 'None' })" -ForegroundColor Gray
    Write-Host "  Trace ID: $($reason.traceId)" -ForegroundColor Gray

    if ($reason.usedProviders.Count -gt 0 -and $reason.failedProviders.Count -eq 0) {
        Write-Host ""
        Write-Host "  [SUCCESS] Routing is working! Multiple providers were used successfully." -ForegroundColor Green
    }
}

# Test 4: Metrics
Write-Host ""
Write-Host "---------------------------------------------------" -ForegroundColor Gray
Write-Host "4. METRICS ENDPOINT" -ForegroundColor Cyan
Write-Host "---------------------------------------------------" -ForegroundColor Gray

$metrics = Test-Endpoint -Name "Metrics Endpoint" -Url "http://localhost:8080/api/v1/metrics"

# Results Summary
Write-Host ""
Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "TEST RESULTS" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan
Write-Host ""

$total = $testsPassed + $testsFailed + $testsSkipped
Write-Host "[PASS] Passed:  $testsPassed"
Write-Host "[FAIL] Failed:  $testsFailed"
Write-Host "[SKIP] Skipped: $testsSkipped"
Write-Host "---" -ForegroundColor Gray
Write-Host "[TOTAL] $total" -ForegroundColor Cyan
Write-Host ""

# Additional diagnostics
Write-Host "ADDITIONAL DIAGNOSTICS:" -ForegroundColor Yellow
Write-Host ""

# Check .env file
if (Test-Path ".env") {
    Write-Host "[OK] .env file exists" -ForegroundColor Green
    $lines = @(Get-Content ".env" | Where-Object { $_ -notmatch '^\s*#' -and $_ -notmatch '^\s*$' })
    Write-Host "   Found $($lines.Count) configuration entries" -ForegroundColor Gray
} else {
    Write-Host "[ERROR] .env file NOT found" -ForegroundColor Red
}

Write-Host ""

# Check if providers are in cooldown
if ($providers) {
    $coolingDown = ($providers | Where-Object { $_.coolingDown }).Count
    if ($coolingDown -gt 0) {
        Write-Host "[WARNING] $coolingDown provider(s) in cooldown" -ForegroundColor Yellow
        Write-Host "   Run: curl -X POST http://localhost:8080/api/v1/providers/{name}/reset-cooldown" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "For more information, check the application logs or visit: http://localhost:8080/swagger-ui.html" -ForegroundColor Cyan
Write-Host ""











