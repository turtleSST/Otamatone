param([string]$PythonPath = 'python')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskGradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$taskStdlib = Get-ChildItem -LiteralPath (Join-Path $taskGradleHome 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.20') -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$taskJavac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { (Get-Command javac).Source }
Push-Location -LiteralPath $taskRoot
try {
    & $PythonPath '-X' 'utf8' 'audio-analysis/prepare_range_validation.py'
    if ($LASTEXITCODE -ne 0) { throw 'Validation preparation failed.' }
    & '.\gradlew.bat' ':app:bundleDebugClassesToRuntimeJar' '--console' 'plain'
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin build failed.' }
    $taskJar = Join-Path $taskRoot 'app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar'
    $taskHarness = Join-Path $taskRoot 'audio-analysis/output/harness'
    New-Item -ItemType Directory -Force -Path $taskHarness | Out-Null
    & $taskJavac '-cp' "$taskJar;$taskStdlib" '-d' $taskHarness 'audio-analysis/RenderRangeFidelity.java'
    if ($LASTEXITCODE -ne 0) { throw 'Render harness compilation failed.' }
    & $taskJava '-cp' "$taskHarness;$taskJar;$taskStdlib" 'RenderRangeFidelity' 'audio-analysis/output/validation_targets.csv' 'audio-analysis/output/rendered'
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin tone rendering failed.' }
    & $taskJava '-cp' "$taskHarness;$taskJar;$taskStdlib" 'RenderRangeFidelity' 'audio-analysis/output/sweep_targets.csv' 'audio-analysis/output/rendered'
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin sweep rendering failed.' }
    & $PythonPath '-X' 'utf8' 'audio-analysis/validate_range_audio.py'
    if ($LASTEXITCODE -ne 0) { throw 'Range comparison failed.' }
} finally { Pop-Location }
