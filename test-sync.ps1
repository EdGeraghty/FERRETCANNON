$headers = @{
    "Authorization" = "Bearer JkNxbPE3UqlPsKDtI-PGKhugFfPVyZHh1ciDQ1WZ7GE"
}

$response = Invoke-RestMethod -Uri "https://ferretcannon.roflcopter.wtf/_matrix/client/v3/sync" -Headers $headers -Method Get

$response | ConvertTo-Json -Depth 20 | Out-File "sync-response.json"
Write-Host "Sync response saved to sync-response.json"
Write-Host "Invited rooms:"
$response.rooms.invite | ConvertTo-Json -Depth 20
