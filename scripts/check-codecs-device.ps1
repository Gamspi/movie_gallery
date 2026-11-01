param(
    [Parameter(Mandatory = $true)][string]$Device,
    [ValidateSet('on', 'prefer')][string]$Mode = 'prefer'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$ProbeAdb = Join-Path $ProjectRoot 'build/android-tools/platform-tools/adb.exe'
$ResultRoot = Join-Path $ProjectRoot 'build/codec-probe'
if (-not (Test-Path -LiteralPath $ProbeAdb)) { throw 'Local adb is missing; use the commands in docs/TESTING.md' }
New-Item -ItemType Directory -Force $ResultRoot | Out-Null
foreach ($Sample in @('aac', 'ac3', 'dts', 'truehd', 'truehd-surround')) {
    & $ProbeAdb -s $Device shell am force-stop dev.gamspi.videogallery
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop probe' }
    & $ProbeAdb -s $Device shell am start -n dev.gamspi.videogallery/.player.CodecProbeActivity --es sample $Sample --es mode $Mode | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot start $Sample" }
    Start-Sleep -Seconds 6
    $CodecPid = & $ProbeAdb -s $Device shell pidof dev.gamspi.videogallery
    if (-not $CodecPid -or $LASTEXITCODE -ne 0) { throw "Probe process disappeared: $Sample" }
    $Lines = & $ProbeAdb -s $Device logcat "--pid=$($CodecPid.Trim())" -d -s CodecProbe:I ffmpeg_jni:E AndroidRuntime:E
    if ($LASTEXITCODE -ne 0) { throw "Cannot read probe log: $Sample" }
    $Log = $Lines -join "`n"
    [IO.File]::WriteAllText((Join-Path $ResultRoot "$Mode-$Sample.log"), $Log, [Text.UTF8Encoding]::new($false))
    if ($Log -match 'errorCode=|FATAL EXCEPTION|E ffmpeg_jni:') { throw "Playback failed: $Mode/$Sample; inspect the saved log" }
    if ($Log -notmatch 'audioPositionAdvancing=true' -or $Log -notmatch 'rendered=[1-9][0-9]*') {
        throw "No audio sink progress: $Mode/$Sample; inspect the saved log"
    }
    if ($Mode -eq 'prefer' -and $Log -notmatch 'audioDecoder=ffmpeg') {
        throw "Software decoder was not selected: $Sample"
    }
    $Decoder = ($Lines | Select-String 'audioDecoder=' | Select-Object -Last 1).Line
    Write-Output "$Mode/$Sample PASS: $Decoder"
}
