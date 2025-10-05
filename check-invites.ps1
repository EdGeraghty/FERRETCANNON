# Check pending invites for ferretcannon user
$db = "ferretcannon.db"

Write-Host "Checking for pending invites..."
sqlite3 $db "SELECT event_id, room_id, sender, content FROM events WHERE type='m.room.member' AND state_key='@ferretcannon:ferretcannon.roflcopter.wtf' AND json_extract(content, '$.membership')='invite' ORDER BY origin_server_ts DESC LIMIT 5;"
