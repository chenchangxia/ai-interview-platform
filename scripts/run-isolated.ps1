param(
  [string]$ConnectionEnvFile = '',
  [string]$GradleUserHome = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($ConnectionEnvFile) {
  if (-not (Test-Path -LiteralPath $ConnectionEnvFile)) {
    throw "Connection env file not found: $ConnectionEnvFile"
  }
  $allowed = @(
    'POSTGRES_HOST',
    'POSTGRES_PORT',
    'POSTGRES_DB',
    'POSTGRES_USER',
    'POSTGRES_PASSWORD'
  )
  foreach ($line in Get-Content -LiteralPath $ConnectionEnvFile) {
    $trimmed = $line.Trim()
    if ($trimmed -match '^([^#=]+)=(.*)$') {
      $key = $Matches[1].Trim()
      if ($allowed -contains $key) {
        $value = $Matches[2].Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
      }
    }
  }
}

$required = @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD')
$missing = $required | Where-Object {
  [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
}
if ($missing.Count -gt 0) {
  throw "Missing database variables: $($missing -join ', ')"
}

if ($GradleUserHome) {
  $env:GRADLE_USER_HOME = $GradleUserHome
}
$env:SPRING_PROFILES_ACTIVE = 'isolated'

Push-Location $repoRoot
try {
  & .\gradlew.bat :app:bootRun --no-daemon
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
