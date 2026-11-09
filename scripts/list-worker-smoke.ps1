$ErrorActionPreference = 'Stop'
$config = @{}
$envPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
foreach ($line in [IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^\s*(LIST_URL|LIST_KEY)\s*=\s*(.*?)\s*$') {
        $config[$Matches[1]] = $Matches[2].Trim('"', "'")
    }
}
if (!$config.LIST_URL -or !$config.LIST_KEY) { throw 'Missing LIST_URL or LIST_KEY in .env' }
$uri = [Uri]$config.LIST_URL
if ($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'Invalid LIST_URL' }
$client = [Net.Http.HttpClient]::new([Net.Http.HttpClientHandler]@{ AllowAutoRedirect = $false })
$client.Timeout = [TimeSpan]::FromSeconds(30)
function Invoke-ListApi($path, $body = $null, $authenticated = $true) {
    $method = if ($null -eq $body) { [Net.Http.HttpMethod]::Get } else { [Net.Http.HttpMethod]::Post }
    $request = [Net.Http.HttpRequestMessage]::new($method, $config.LIST_URL.TrimEnd('/') + $path)
    if ($authenticated) { $request.Headers.Add('X-VG-List-Key', $config.LIST_KEY) }
    if ($null -ne $body) { $request.Content = [Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 8 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        try { $data = $text | ConvertFrom-Json } catch { throw 'Non-JSON API response' }
        return @{ status = $status; data = $data }
    } catch { throw 'API request failed (details suppressed to protect configuration)' }
    finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
function Assert-Smoke($condition, $label) {
    if (!$condition) { throw "FAIL: $label" }
    Write-Output "PASS: $label"
}
$row = $null
$attempted = $false
try {
    $health = Invoke-ListApi '/v1/health'
    Assert-Smoke ($health.status -eq 200 -and $health.data.ok) 'health'
    $since = $health.data.seq
    $anonymous = Invoke-ListApi '/v1/health' $null $false
    Assert-Smoke ($anonymous.status -eq 403 -and $anonymous.data.error -eq 'bad_key') 'unauthenticated request rejected'
    $row = @{ key = 'ai-smoke-' + [Guid]::NewGuid().ToString('N'); data = @{ name = 'Проверка API' }; updated_at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); deleted = $false }
    $body = @{ titles = @($row) }
    $attempted = $true
    $write = Invoke-ListApi '/v1/batch' $body
    Assert-Smoke ($write.status -eq 200 -and $write.data.applied -eq 1 -and $write.data.seq -gt $since) 'insert'
    $retry = Invoke-ListApi '/v1/batch' $body
    Assert-Smoke ($retry.status -eq 200 -and $retry.data.applied -eq 0 -and $retry.data.seq -ge $write.data.seq) 'retry ignored'
    $found = $false
    do {
        $page = Invoke-ListApi "/v1/changes?since=$since"
        Assert-Smoke ($page.status -eq 200) 'changes page'
        foreach ($item in $page.data.titles) { if ($item.key -eq $row.key -and !$item.deleted -and $item.data.name -eq $row.data.name) { $found = $true } }
        $since = $page.data.next
    } while ($page.data.more)
    Assert-Smoke $found 'insert visible'
} finally {
    try {
        if ($attempted) {
            $row.deleted = $true
            $row.updated_at = [Math]::Max($row.updated_at + 1, [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
            $cleanup = Invoke-ListApi '/v1/batch' @{ titles = @($row) }
            Assert-Smoke ($cleanup.status -eq 200 -and $cleanup.data.applied -eq 1) 'test row tombstoned'
            $cursor = $cleanup.data.seq - 1
            $deleted = Invoke-ListApi "/v1/changes?since=$cursor&limit=1"
            Assert-Smoke ($deleted.status -eq 200 -and $deleted.data.titles.Count -eq 1 -and $deleted.data.titles[0].key -eq $row.key -and $deleted.data.titles[0].deleted) 'tombstone visible'
        }
    } finally { $client.Dispose() }
}
