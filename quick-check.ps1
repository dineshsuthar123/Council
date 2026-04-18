# Simple test script that works even if app isn't running

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Council Application - Simple Test" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Check 1: .env file
Write-Host "1. Checking .env file..." -ForegroundColor Yellow
if (Test-Path ".env") {
    Write-Host "   [OK] .env file exists" -ForegroundColor Green
    $lines = @(Get-Content ".env" | Where-Object { $_ -notmatch '^\s*#' -and $_ -notmatch '^\s*$' })
    Write-Host "   [OK] Found $($lines.Count) configuration entries" -ForegroundColor Green
} else {
    Write-Host "   [ERROR] .env file NOT found" -ForegroundColor Red
}

Write-Host ""

# Check 2: Application running
Write-Host "2. Checking if application is running..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/health" -UseBasicParsing -ErrorAction Stop
    Write-Host "   [OK] Application is running on port 8080" -ForegroundColor Green

    # Get health details
    $health = $response.Content | ConvertFrom-Json
    Write-Host "   [OK] Status: $($health.status)" -ForegroundColor Green
    Write-Host "   [OK] Routing Enabled: $($health.routingEnabled)" -ForegroundColor Green
    Write-Host "   [OK] Available Providers: $($health.availableProviders.Count)" -ForegroundColor Green
} catch {
    Write-Host "   [WARNING] Application NOT running on localhost:8080" -ForegroundColor Yellow
    Write-Host "   [INFO] Start it with: .\run.ps1" -ForegroundColor Cyan
}

Write-Host ""

# Check 3: Java and Maven
Write-Host "3. Checking Java and Maven..." -ForegroundColor Yellow
try {
    $javaVersion = & java -version 2>&1 | Select-String "version"
    Write-Host "   [OK] Java is installed: $($javaVersion[0])" -ForegroundColor Green
} catch {
    Write-Host "   [ERROR] Java not found" -ForegroundColor Red
}

try {
    $mvnVersion = & mvn -version 2>&1 | Select-String "Apache Maven"
    Write-Host "   [OK] Maven is installed: $($mvnVersion[0])" -ForegroundColor Green
} catch {
    Write-Host "   [ERROR] Maven not found" -ForegroundColor Red
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Setup Check Complete" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "NEXT STEPS:" -ForegroundColor Yellow
Write-Host ""
Write-Host "1. If application is NOT running:" -ForegroundColor Cyan
Write-Host "   Run: .\run.ps1" -ForegroundColor White
Write-Host "   (This will start the app and keep it running)" -ForegroundColor Gray
Write-Host ""
Write-Host "2. Once application is running, test with:" -ForegroundColor Cyan
Write-Host "   curl -X POST http://localhost:8080/api/v1/reason \" -ForegroundColor White
Write-Host "     -H 'Content-Type: application/json' \" -ForegroundColor White
Write-Host "     -Body '{""query"":""What is 2+2?""}'" -ForegroundColor White
Write-Host ""
Write-Host "3. Or run comprehensive tests:" -ForegroundColor Cyan
Write-Host "   .\full-test.ps1" -ForegroundColor White
Write-Host ""

