# Usage: scripts/gradle-docker.ps1 <command...>   e.g. ./gradlew testDebugUnitTest
$Tag = 'videogallery-build'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
docker image inspect $Tag *> $null
if ($LASTEXITCODE -ne 0) {
    docker build -t $Tag (Join-Path $Root 'docker')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
docker run --rm -v "${Root}:/work" -v videogallery-gradle-cache:/root/.gradle -w /work $Tag @args
exit $LASTEXITCODE
