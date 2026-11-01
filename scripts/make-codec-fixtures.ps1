param(
    [Parameter(Mandatory = $true)]
    [string]$Image
)

# Supply an immutable local image ID or registry digest, never a moving tag.
$ErrorActionPreference = 'Stop'
if ($Image -notmatch '(^sha256:[a-f0-9]{64}$|@sha256:[a-f0-9]{64}$)') {
    throw 'Image must be an immutable sha256 image ID or registry digest'
}
$FixtureRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/codec-probe'
New-Item -ItemType Directory -Force -Path $FixtureRoot | Out-Null
$ImageId = docker image inspect $Image --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'The pinned FFmpeg image must be available locally' }
$Version = docker run --rm --entrypoint ffmpeg $Image -version
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the FFmpeg version' }

$Encoders = [ordered]@{ aac = 'aac'; ac3 = 'ac3'; dts = 'dca'; truehd = 'truehd'; 'truehd-surround' = 'truehd' }
foreach ($Codec in $Encoders.Keys) {
    $Channels = if ($Codec -eq 'truehd-surround') { '6' } else { '2' }
    $AudioArgs = @('-c:a', $Encoders[$Codec], '-ac', $Channels, '-ar', '48000')
    if ($Codec -eq 'aac') { $AudioArgs += @('-b:a', '192k') }
    if ($Codec -eq 'ac3') { $AudioArgs += @('-b:a', '384k') }
    if ($Codec -eq 'dts') { $AudioArgs += @('-strict', '-2', '-b:a', '768k') }
    if ($Codec -like 'truehd*') { $AudioArgs += @('-strict', '-2') }
    docker run --rm -v "${FixtureRoot}:/w" --entrypoint ffmpeg $Image `
        -hide_banner -loglevel error -y `
        -f lavfi -i 'testsrc2=size=320x180:rate=24:duration=20' `
        -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=20' `
        -map 0:v -map 1:a -c:v libx264 -preset ultrafast -pix_fmt yuv420p `
        @AudioArgs -metadata:s:a:0 "title=$Codec synthetic 440Hz" -shortest "/w/$Codec.mkv"
    if ($LASTEXITCODE -ne 0) { throw "FFmpeg fixture failed: $Codec" }
}

docker run --rm -v "${FixtureRoot}:/w" --entrypoint ffmpeg $Image `
    -hide_banner -loglevel error -y -i /w/aac.mkv -i /w/ac3.mkv -i /w/dts.mkv -i /w/truehd.mkv `
    -map 0:v -map 0:a -map 1:a -map 2:a -map 3:a -c copy `
    -metadata:s:a:0 title=AAC -metadata:s:a:1 title=AC3 `
    -metadata:s:a:2 title=DTS -metadata:s:a:3 title=TrueHD /w/all-codecs.mkv
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg mixed-track fixture failed' }

$Files = foreach ($Name in @('aac', 'ac3', 'dts', 'truehd', 'truehd-surround', 'all-codecs')) {
    $Path = Join-Path $FixtureRoot "$Name.mkv"
    [ordered]@{ file = "$Name.mkv"; bytes = (Get-Item -LiteralPath $Path).Length; sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
}
$Manifest = [ordered]@{
    source = 'Synthetic testsrc2 video and 440 Hz sine, 20 seconds, stereo 48 kHz; extra TrueHD 5.1 regression fixture'
    image = $ImageId.Trim()
    ffmpeg = $Version
    files = @($Files)
}
[IO.File]::WriteAllText((Join-Path $FixtureRoot 'manifest.json'), ($Manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output $FixtureRoot
