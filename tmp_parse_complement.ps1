$lines = Get-Content -Path '.\ci-artifacts\18918348381\complement-output.json'
$objects = @()
foreach ($line in $lines) {
  try {
    $obj = $line | ConvertFrom-Json -ErrorAction Stop
    if ($obj.Test) { $objects += $obj }
  } catch { }
}
# Group by Test and take last event
$final = $objects | Group-Object -Property Test | ForEach-Object {
  $name = $_.Name
  $action = ($_.Group | Select-Object -Last 1).Action
  [PSCustomObject]@{ Test = $name; Action = $action }
}
# Counts
$counts = $final | Group-Object -Property Action | ForEach-Object { @{ Action = $_.Name; Count = $_.Count } }
$counts | ForEach-Object { "{0}: {1}" -f $_.Action, $_.Count }
# List failed tests
$final | Where-Object { $_.Action -eq 'fail' } | Select-Object -ExpandProperty Test | Sort-Object | ForEach-Object { "- $_" }
