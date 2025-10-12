# check-join-sync.ps1
# Safe helper: POST /join and GET /sync without exiting the shell.
# Usage: paste and run in PowerShell, paste your access token when prompted.

$SERVER = if ($env:SERVER -and $env:SERVER -ne '') { $env:SERVER } else { 'https://ferretcannon.roflcopter.wtf' }
$ROOM   = '!ljI2RJQdNNdO7hOrzMPrACPxwQZiKxMJhCTJtMXx-hw'

$TOKEN = Read-Host -Prompt 'Enter access token (paste token and press Enter)'

$headers = @{ Authorization = "Bearer $TOKEN"; 'Content-Type' = 'application/json' }

function Show-RawResponse($respObj) {
  if ($null -eq $respObj) { Write-Host '<no response>'; return }
  try {
    if ($null -ne $respObj.StatusCode) { Write-Host "StatusCode: $($respObj.StatusCode)" }
    if ($null -ne $respObj.StatusDescription) { Write-Host "StatusDescription: $($respObj.StatusDescription)" }
  } catch {}
  Write-Host '---- Headers ----'
  try { foreach ($k in $respObj.Headers.Keys) { Write-Host ("{0}: {1}" -f $k, $respObj.Headers[$k]) } } catch {}
  Write-Host '---- Body ----'
  try {
    if ($respObj.PSObject.Properties.Name -contains 'Content' -and $respObj.Content) {
      Write-Host $respObj.Content
    } elseif ($respObj.PSObject.Properties.Name -contains 'RawContent' -and $respObj.RawContent) {
      Write-Host $respObj.RawContent
    } else {
      try {
        $stream = $respObj.GetResponseStream()
        if ($stream) {
          $sr = New-Object System.IO.StreamReader($stream)
          $body = $sr.ReadToEnd()
          if ($body) { Write-Host $body } else { Write-Host '<empty body>' }
        } else {
          Write-Host '<no readable body found>'
        }
      } catch {
        Write-Host '<no readable body found>'
      }
    }
  } catch {
    Write-Host "<failed to read body: $_>"
  }
  Write-Host '-----------------' ; Write-Host ''
}

# POST join (non-fatal)
$joinUri = "$SERVER/_matrix/client/v3/rooms/$ROOM/join"
Write-Host "POST $joinUri"
# If testing against localhost, include server_name in the join body to satisfy implementations that require it
$joinBody = '{}'
if ($SERVER -match 'localhost') { $joinBody = '{"server_name":"localhost"}' }
try {
  $joinResp = Invoke-WebRequest -Uri $joinUri -Method Post -Headers $headers -Body $joinBody -UseBasicParsing -ErrorAction Stop
  Show-RawResponse $joinResp
} catch {
  Write-Host 'Join request failed (non-fatal):'
  if ($_.Exception.Response) { Show-RawResponse $_.Exception.Response } else { $_ | Format-List -Force }
  Write-Host 'Continuing without exiting the shell...'
}

# GET quick sync (non-fatal)
$syncUri = "$SERVER/_matrix/client/v3/sync?timeout=0"
Write-Host "`nGET $syncUri"
try {
  $syncResp = Invoke-WebRequest -Uri $syncUri -Method Get -Headers $headers -UseBasicParsing -ErrorAction Stop
  Show-RawResponse $syncResp
} catch {
  Write-Host 'Sync request failed (non-fatal):'
  if ($_.Exception.Response) { Show-RawResponse $_.Exception.Response } else { $_ | Format-List -Force }
  Write-Host 'Done — no exit performed.'
}
