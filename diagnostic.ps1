# PowerShell diagnostic script for Council Provider Configuration

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Council Provider Configuration Diagnostic" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Check environment variables
Write-Host "1. Checking Environment Variables..." -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor Yellow

$keys = @(
    "DEEPSEEK_API_KEY",
    "GEMINI_API_KEY",
    "CLAUDE_API_KEY",
    "OPENROUTER_API_KEY",
    "GROQ_API_KEY",
    "TOGETHER_API_KEY",
    "MISTRAL_API_KEY",
    "KIMI_API_KEY",
    "HUGGINGFACE_API_KEY"
)

foreach ($key in $keys) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ([string]::IsNullOrEmpty($value)) {
        Write-Host "❌ $key : NOT SET" -ForegroundColor Red
    } else {
        $prefix = $value.Substring(0, [Math]::Min(10, $value.Length))
        Write-Host "✅ $key : $prefix..." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "2. Application Status..." -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor Yellow

# Check if app is running
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/health" -ErrorAction Stop
    Write-Host "✅ Application is running on localhost:8080" -ForegroundColor Green

    # Get health status
    Write-Host ""
    Write-Host "3. Health Endpoint..." -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
    $health = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/health" | ConvertFrom-Json
    $health | ConvertTo-Json | Write-Host

    # Get provider status
    Write-Host ""
    Write-Host "4. Provider Status (Simplified)..." -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
    try {
        $providers = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/providers/status" | ConvertFrom-Json
        foreach ($p in $providers) {
            Write-Host "Provider: $($p.provider) | Enabled: $($p.enabled) | Cooldown: $($p.coolingDown) | Model: $($p.model)" -ForegroundColor Gray
        }
    } catch {
        Write-Host "Could not fetch provider status" -ForegroundColor Yellow
    }

    # Get metrics
    Write-Host ""
    Write-Host "5. Application Metrics..." -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
    try {
        $metrics = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/metrics" | ConvertFrom-Json
        $metrics.providers | ConvertTo-Json | Write-Host
    } catch {
        Write-Host "Could not fetch metrics" -ForegroundColor Yellow
    }

} catch {
    Write-Host "❌ Application is NOT running on localhost:8080" -ForegroundColor Red
    Write-Host "   Start it with: mvn spring-boot:run" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Diagnostics Complete" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "TROUBLESHOOTING STEPS:" -ForegroundColor Yellow
Write-Host "1. If API keys are NOT SET: Set them in your environment:" -ForegroundColor Gray
Write-Host '   $env:DEEPSEEK_API_KEY = "sk-your-key-here"' -ForegroundColor Gray
Write-Host ""
Write-Host "2. If app is NOT RUNNING: Start it with:" -ForegroundColor Gray
Write-Host "   mvn spring-boot:run" -ForegroundColor Gray
Write-Host ""
Write-Host "3. If providers show coolingDown=true: Wait 15 minutes or reset:" -ForegroundColor Gray
Write-Host "   curl -X POST http://localhost:8080/api/v1/providers/{name}/reset-cooldown" -ForegroundColor Gray
Write-Host ""
Write-Host "4. If all providers show enabled=false: Check your application.yml" -ForegroundColor Gray
Write-Host "   Make sure 'enabled: true' and API keys are non-empty" -ForegroundColor Gray

