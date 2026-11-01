param([Parameter(Mandatory = $true)][string]$Device)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$ProbeAdb = Join-Path $ProjectRoot 'build/android-tools/platform-tools/adb.exe'
$ResultRoot = Join-Path $ProjectRoot 'build/codec-probe'
function Send-Probe([string[]]$Extras) {
    & $ProbeAdb -s $Device shell am start -n dev.gamspi.videogallery/.player.CodecProbeActivity @Extras | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot send probe command' }
}
function Read-ProbeLog {
    $CodecPid = & $ProbeAdb -s $Device shell pidof dev.gamspi.videogallery
    if (-not $CodecPid) { throw 'Probe process disappeared' }
    $Lines = & $ProbeAdb -s $Device logcat "--pid=$($CodecPid.Trim())" -d -s CodecProbe:I ffmpeg_jni:E AndroidRuntime:E
    $Log = $Lines -join "`n"
    if ($Log -match 'errorCode=|FATAL EXCEPTION|E ffmpeg_jni:') { throw 'Decoder error; inspect device log' }
    return $Log
}
function Wait-Position([long]$Minimum, [long]$Maximum, [string]$Playing = 'true', [string]$Codec = '') {
    for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
        Start-Sleep -Seconds 1
        $Log = Read-ProbeLog
        $Positions = [regex]::Matches($Log, 'position=(\d+) playing=(true|false) rendered=(\d+)')
        if ($Positions.Count -eq 0) { continue }
        $Last = $Positions[$Positions.Count - 1]
        $Position = [long]$Last.Groups[1].Value
        $Decoders = [regex]::Matches($Log, 'audioDecoder=(\S+)')
        $CorrectCodec = $Codec -eq '' -or ($Decoders.Count -gt 0 -and $Decoders[$Decoders.Count - 1].Groups[1].Value.EndsWith("-$Codec"))
        if ($Position -ge $Minimum -and $Position -le $Maximum -and
            $Last.Groups[2].Value -eq $Playing -and [long]$Last.Groups[3].Value -gt 0 -and $CorrectCodec) {
            return $Position
        }
    }
    throw "No confirmed audio position in [$Minimum,$Maximum], playing=$Playing codec=$Codec"
}
function Save-ProbeLog([string]$Name) {
    $Log = Read-ProbeLog
    [IO.File]::WriteAllText((Join-Path $ResultRoot "$Name.log"), $Log, [Text.UTF8Encoding]::new($false))
}
foreach ($Sample in @('truehd', 'truehd-surround')) {
    & $ProbeAdb -s $Device shell am force-stop dev.gamspi.videogallery
    Send-Probe @('--es', 'sample', $Sample, '--es', 'mode', 'prefer')
    Wait-Position 100 19000 'true' 'truehd' | Out-Null
    Send-Probe @('--el', 'seekMs', '10000')
    Wait-Position 10000 15000 'true' 'truehd' | Out-Null
    Send-Probe @('--es', 'command', 'pause')
    $Paused = Wait-Position 10000 16000 'false' 'truehd'
    Start-Sleep -Seconds 2
    $StillPaused = Wait-Position 10000 16000 'false' 'truehd'
    if ([Math]::Abs($StillPaused - $Paused) -gt 50) { throw "Clock advanced while paused: $Sample" }
    Send-Probe @('--el', 'seekMs', '3000')
    Wait-Position 2990 3050 'false' 'truehd' | Out-Null
    Send-Probe @('--es', 'command', 'play')
    Wait-Position 3500 10000 'true' 'truehd' | Out-Null
    Save-ProbeLog "controls-$Sample"
    Write-Output "$Sample pause/seek 10s/back 3s/play PASS; paused clock $Paused -> $StillPaused ms"
}
& $ProbeAdb -s $Device shell am force-stop dev.gamspi.videogallery
Send-Probe @('--es', 'sample', 'all-codecs', '--es', 'mode', 'prefer')
Wait-Position 100 19000 'true' 'aac' | Out-Null
$Expected = @{1='ac3'; 2='dca'; 3='truehd'; 0='aac'}
foreach ($TrackIndex in @(1, 2, 3, 0)) {
    Send-Probe @('--ei', 'audioIndex', "$TrackIndex")
    Send-Probe @('--el', 'seekMs', '0')
    Wait-Position 100 10000 'true' $Expected[$TrackIndex] | Out-Null
    Start-Sleep -Seconds 1
}
$Log = Read-ProbeLog
Save-ProbeLog 'controls-mixed'
foreach ($Codec in @('aac', 'ac3', 'dca', 'truehd')) {
    if ($Log -notmatch "audioDecoder=ffmpeg[^`r`n]*-$Codec ") { throw "Missing mixed-track decoder: $Codec" }
}
if (($Log -split 'audioPositionAdvancing=true').Count -lt 5) { throw 'Mixed-track output did not advance after each selection' }
Write-Output 'Mixed AAC -> AC3 -> DTS -> TrueHD -> AAC PASS'
