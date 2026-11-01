# Synthetic, copyright-free fixture for the debug dual-text probe.
$ErrorActionPreference = 'Stop'
$FixtureRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/dual-text-probe'
New-Item -ItemType Directory -Force -Path $FixtureRoot | Out-Null
foreach ($Language in @('en', 'ru')) {
    $Cues = for ($CueIndex = 0; $CueIndex -lt 20; $CueIndex++) {
        $Start = ([TimeSpan]::FromSeconds($CueIndex * 9)).ToString('hh\:mm\:ss') + ',000'
        $End = ([TimeSpan]::FromSeconds($CueIndex * 9 + 6)).ToString('hh\:mm\:ss') + ',000'
        $Label = if ($Language -eq 'en') { 'English cue' } else { 'Русский текст' }
        "$($CueIndex + 1)`n$Start --> $End`n$Label $($CueIndex + 1)`n"
    }
    [IO.File]::WriteAllText(
        (Join-Path $FixtureRoot "$Language.srt"),
        ($Cues -join "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}
docker run --rm -v "${FixtureRoot}:/w" --entrypoint ffmpeg linuxserver/ffmpeg:latest `
    -hide_banner -loglevel error -y -f lavfi -i color=c=0x203040:s=640x360:r=24:d=180 `
    -i /w/en.srt -i /w/ru.srt -map 0:v -map 1 -map 2 `
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:s srt `
    -metadata:s:s:0 language=eng -metadata:s:s:1 language=rus /w/subs2.mkv
if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed: $LASTEXITCODE" }
foreach ($Variant in @('no-subs', 'english-only')) {
    $SubtitleMap = if ($Variant -eq 'english-only') { @('-map', '0:s:0') } else { @() }
    docker run --rm -v "${FixtureRoot}:/w" --entrypoint ffmpeg linuxserver/ffmpeg:latest `
        -hide_banner -loglevel error -y -i /w/subs2.mkv -map 0:v @SubtitleMap -c copy "/w/$Variant.mkv"
    if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed: $LASTEXITCODE" }
}
Write-Output (Join-Path $FixtureRoot 'subs2.mkv')
