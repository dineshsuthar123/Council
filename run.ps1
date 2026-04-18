# Simple PowerShell script to start Council with .env support

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Council Application - Auto Start" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

$projectRoot = Get-Location

# Check .env file exists
if (-not (Test-Path ".env")) {
    Write-Host "ERROR: .env file not found!" -ForegroundColor Red
    Write-Host "   Please create a .env file with API keys" -ForegroundColor Yellow
    exit 1
}

Write-Host "Found .env file" -ForegroundColor Green
Write-Host "Starting application with auto-loaded environment variables..." -ForegroundColor Green
Write-Host ""
Write-Host "The app will be available at: http://localhost:8080" -ForegroundColor Yellow
Write-Host "Swagger UI at: http://localhost:8080/swagger-ui.html" -ForegroundColor Yellow
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Yellow
Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Set JAVA_HOME
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# Run Maven with spring-boot:run
# spring-dotenv library will automatically load .env file
& mvn spring-boot:run

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Application stopped" -ForegroundColor Yellow


