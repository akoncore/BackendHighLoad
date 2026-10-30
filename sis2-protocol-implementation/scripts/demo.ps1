param([string]$Base = 'http://127.0.0.1:8080/api/v1/products')
$ErrorActionPreference = 'Stop'
$key = [guid]::NewGuid().ToString()
$body = @{ title='Laptop'; price='750000.00'; stock=10; currency='KZT'; category_id=1 } | ConvertTo-Json
$headers = @{ 'Idempotency-Key'=$key }
$first = Invoke-RestMethod -Method Post -Uri $base -Headers $headers -ContentType 'application/json' -Body $body
$again = Invoke-RestMethod -Method Post -Uri $base -Headers $headers -ContentType 'application/json' -Body $body
if ($first.id -ne $again.id) { throw 'Duplicate product!' }
Write-Host "Idempotency OK. Product id: $($first.id)"
$first | ConvertTo-Json
Invoke-RestMethod -Uri "$base/$($first.id)" | ConvertTo-Json
$updated = Invoke-RestMethod -Method Put -Uri "$base/$($first.id)" -Headers @{ 'If-Match'='"1"' } -ContentType 'application/json' -Body $body
Write-Host "New version: $($updated.version)"
Invoke-RestMethod -Method Delete -Uri "$base/$($first.id)"
Write-Host 'CRUD OK. Demo product deleted.'
