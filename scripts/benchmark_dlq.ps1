param(
  [string]$BaseUrl = 'http://127.0.0.1:18080',
  [int]$Count = 100,
  [int]$TimeoutSeconds = 8,
  [int]$ReplayIntervalMs = 450
)

$ErrorActionPreference = 'Stop'

function Get-Percentile([System.Collections.Generic.List[double]]$Values, [double]$P) {
  if ($Values.Count -eq 0) {
    return $null
  }
  $sorted = @($Values | Sort-Object)
  $index = [Math]::Ceiling($P * $sorted.Count) - 1
  $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
  return [Math]::Round([double]$sorted[$index], 2)
}

$deadLetterLatencies = [System.Collections.Generic.List[double]]::new()
$recoveryLatencies = [System.Collections.Generic.List[double]]::new()
$captured = 0
$replayAccepted = 0
$completed = 0
$errors = [System.Collections.Generic.List[string]]::new()
$wall = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Count; $i++) {
  try {
    $deadLetterWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $created = Invoke-RestMethod -Method Post "$BaseUrl/api/demo/async/failures"
    $taskId = $created.data.taskId
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $deadLetter = $null

    while ([DateTime]::UtcNow -lt $deadline -and -not $deadLetter) {
      $pending = Invoke-RestMethod "$BaseUrl/api/admin/async/dead-letters?status=PENDING&limit=200"
      $deadLetter = $pending.data |
        Where-Object { $_.payloadSummary.taskId -eq $taskId } |
        Select-Object -First 1
      if (-not $deadLetter) {
        Start-Sleep -Milliseconds 50
      }
    }

    if (-not $deadLetter) {
      $errors.Add("task $taskId was not captured by DLQ")
      Start-Sleep -Milliseconds $ReplayIntervalMs
      continue
    }
    $deadLetterWatch.Stop()
    $captured++
    $deadLetterLatencies.Add($deadLetterWatch.Elapsed.TotalMilliseconds)

    $recoveryWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $replayed = Invoke-RestMethod -Method Post `
      "$BaseUrl/api/admin/async/dead-letters/$($deadLetter.id)/replay"
    if ($replayed.code -ne 200 -or $replayed.data.status -ne 'REPLAYED') {
      $errors.Add("dead letter $($deadLetter.id) replay was rejected")
      Start-Sleep -Milliseconds $ReplayIntervalMs
      continue
    }
    $replayAccepted++

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $taskStatus = 'UNKNOWN'
    while ([DateTime]::UtcNow -lt $deadline -and $taskStatus -ne 'COMPLETED') {
      $statusResponse = Invoke-RestMethod "$BaseUrl/api/demo/async/tasks/$taskId"
      $taskStatus = $statusResponse.data.status
      if ($taskStatus -ne 'COMPLETED') {
        Start-Sleep -Milliseconds 50
      }
    }
    $recoveryWatch.Stop()

    if ($taskStatus -eq 'COMPLETED') {
      $completed++
      $recoveryLatencies.Add($recoveryWatch.Elapsed.TotalMilliseconds)
    } else {
      $errors.Add("task $taskId did not complete after replay")
    }
  } catch {
    $errors.Add("iteration $i failed: $($_.Exception.Message)")
  }

  # 重放接口同时受全局每秒5次和IP每秒3次滑动窗口限流，保持在安全速率内。
  Start-Sleep -Milliseconds $ReplayIntervalMs
}

$wall.Stop()
[pscustomobject]@{
  requested = $Count
  dead_letter_captured = $captured
  dead_letter_capture_rate_percent = [Math]::Round(100.0 * $captured / $Count, 2)
  replay_accepted = $replayAccepted
  completed_after_replay = $completed
  replay_recovery_rate_percent = if ($replayAccepted -eq 0) {
    0
  } else {
    [Math]::Round(100.0 * $completed / $replayAccepted, 2)
  }
  dead_letter_latency_ms = [ordered]@{
    p50 = Get-Percentile $deadLetterLatencies 0.50
    p95 = Get-Percentile $deadLetterLatencies 0.95
    max = if ($deadLetterLatencies.Count -eq 0) {
      $null
    } else {
      [Math]::Round(($deadLetterLatencies | Measure-Object -Maximum).Maximum, 2)
    }
  }
  replay_to_completion_latency_ms = [ordered]@{
    p50 = Get-Percentile $recoveryLatencies 0.50
    p95 = Get-Percentile $recoveryLatencies 0.95
    max = if ($recoveryLatencies.Count -eq 0) {
      $null
    } else {
      [Math]::Round(($recoveryLatencies | Measure-Object -Maximum).Maximum, 2)
    }
  }
  wall_time_seconds = [Math]::Round($wall.Elapsed.TotalSeconds, 2)
  errors = @($errors)
  notes = 'Local isolated profile; synthetic Redis Stream failures; no external LLM calls.'
} | ConvertTo-Json -Depth 5
