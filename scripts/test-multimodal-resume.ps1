#requires -Version 7.0

param(
  [Parameter(Mandatory = $true)]
  [string]$ImagePath,

  [string]$BaseUrl = 'http://localhost:8080',

  [switch]$WaitForAnalysis,

  [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

$resolvedImage = Resolve-Path -LiteralPath $ImagePath -ErrorAction Stop
$file = Get-Item -LiteralPath $resolvedImage
$supportedExtensions = @('.png', '.jpg', '.jpeg', '.webp')
if ($supportedExtensions -notcontains $file.Extension.ToLowerInvariant()) {
  throw "Unsupported image extension '$($file.Extension)'. Use PNG, JPG, JPEG or WEBP."
}

$healthUrl = "$($BaseUrl.TrimEnd('/'))/api/resumes/health"
$uploadUrl = "$($BaseUrl.TrimEnd('/'))/api/resumes/upload"

Write-Host "[1/4] Checking backend: $healthUrl"
$health = Invoke-RestMethod -Method Get -Uri $healthUrl
if ($health.code -ne 200) {
  throw "Backend health check failed: $($health | ConvertTo-Json -Depth 6)"
}

Write-Host "[2/4] Uploading image: $($file.FullName)"
Write-Host "      This request calls the configured vision model and may incur a small charge."
$upload = Invoke-RestMethod -Method Post -Uri $uploadUrl -Form @{ file = $file }
if ($upload.code -ne 200) {
  throw "Upload failed: $($upload | ConvertTo-Json -Depth 8)"
}
if ($upload.data.inputModality -ne 'IMAGE') {
  throw "Expected inputModality=IMAGE, actual=$($upload.data.inputModality)"
}

$resumeId = $upload.data.storage.resumeId
if (-not $resumeId) {
  throw "Upload response does not contain storage.resumeId"
}

Write-Host "[3/4] Reading persisted multimodal extraction: resumeId=$resumeId"
$detailUrl = "$($BaseUrl.TrimEnd('/'))/api/resumes/$resumeId/detail"
$detail = Invoke-RestMethod -Method Get -Uri $detailUrl
$resumeText = [string]$detail.data.resumeText
if ([string]::IsNullOrWhiteSpace($resumeText)) {
  throw "Vision extraction returned blank resumeText"
}

$previewLength = [Math]::Min(300, $resumeText.Length)
Write-Host "      inputModality: IMAGE"
Write-Host "      extractedChars: $($resumeText.Length)"
Write-Host "      preview:"
Write-Host $resumeText.Substring(0, $previewLength)

if (-not $WaitForAnalysis) {
  Write-Host "[4/4] PASS - image routing, vision extraction and persistence are valid."
  Write-Host "      Re-run with -WaitForAnalysis to wait for the Redis Stream scoring task."
  exit 0
}

Write-Host "[4/4] Waiting for Redis Stream analysis (timeout: ${TimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
  Start-Sleep -Seconds 2
  $detail = Invoke-RestMethod -Method Get -Uri $detailUrl
  $status = [string]$detail.data.analyzeStatus
  Write-Host "      status=$status"
  if ($status -eq 'COMPLETED') {
    Write-Host "PASS - multimodal extraction and asynchronous resume analysis completed."
    exit 0
  }
  if ($status -eq 'FAILED') {
    throw "Async analysis failed: $($detail.data.analyzeError)"
  }
} while ((Get-Date) -lt $deadline)

throw "Timed out waiting for analysis. Last status=$status"
