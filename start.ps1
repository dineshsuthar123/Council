# PowerShell script to load .env file and start the Council application

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "Council Application Startup" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Load .env file
$envFile = ".env"
if (Test-Path $envFile) {
    Write-Host "Loading environment variables from $envFile..." -ForegroundColor Yellow

    $content = Get-Content $envFile | Where-Object { $_ -notmatch '^\s*#' -and $_ -notmatch '^\s*$' }

    foreach ($line in $content) {
        $parts = $line -split '=', 2
        if ($parts.Count -eq 2) {
            $key = $parts[0].Trim()
            $value = $parts[1].Trim()

            # Set environment variable
            [Environment]::SetEnvironmentVariable($key, $value)

            # Show masked output for security
            if ($value.Length -gt 20) {
                $masked = $value.Substring(0, 10) + "..." + $value.Substring($value.Length - 5)
            } else {
                $masked = "***"
            }

            Write-Host "  ✓ $key = $masked" -ForegroundColor Green
        }
    }
    Write-Host ""
    Write-Host "✅ Environment variables loaded successfully" -ForegroundColor Green
} else {
    Write-Host "❌ .env file not found at $envFile" -ForegroundColor Red
    Write-Host "Please create a .env file with API keys." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "Starting Council application..." -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor Yellow
Write-Host ""

# Navigate to project directory
$projectDir = (Get-Location).Path

# Start Maven
Write-Host "Running: mvn spring-boot:run" -ForegroundColor Cyan
Write-Host ""

$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
& mvn spring-boot:run

Write-Host ""
Write-Host "Application stopped." -ForegroundColor Yellow

