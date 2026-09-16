param([Parameter(Mandatory=$true)][string]$Serial,[string]$PythonPath='python')
$ErrorActionPreference='Stop'
if (-not $Serial.StartsWith('emulator-')) { throw 'This smoke test resets app installs and is limited to an explicitly selected emulator.' }
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$taskSigner=Join-Path $taskSdk 'build-tools/35.0.0/apksigner.bat'
$taskOut=Join-Path $taskRoot 'verification-output'
New-Item -ItemType Directory -Force -Path $taskOut | Out-Null
Push-Location -LiteralPath $taskRoot
try {
    & $PythonPath 'scripts/sign_test_runner.py' '--apksigner' $taskSigner '--output' (Join-Path $taskOut 'release-smoke-test.apk')
    if($LASTEXITCODE -ne 0){throw 'Test runner signing failed.'}
    adb -s $Serial uninstall com.tadpole.instrument.releasecheck | Out-Null
    adb -s $Serial uninstall com.tadpole.instrument.test | Out-Null
    adb -s $Serial uninstall com.tadpole.instrument | Out-Null
    adb -s $Serial install app/build/outputs/apk/release/app-release.apk
    if($LASTEXITCODE -ne 0){throw 'Release installation failed.'}
    adb -s $Serial install -t (Join-Path $taskOut 'release-smoke-test.apk')
    if($LASTEXITCODE -ne 0){throw 'Smoke test installation failed.'}
    $taskLog=Join-Path $taskOut 'release-smoke-log.txt'
    $taskApkHash=(Get-FileHash -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskOutput=& adb -s $Serial shell am instrument -w -e releaseSmoke true -e class com.tadpole.instrument.ReleaseSmokeTest com.tadpole.instrument.releasecheck/androidx.test.runner.AndroidJUnitRunner
    $taskResult=$taskOutput -join [Environment]::NewLine
    $taskResult | Set-Content -LiteralPath $taskLog -Encoding utf8
    if($taskResult -notmatch 'OK \(1 test\)'){throw 'Release smoke test failed; see verification-output/release-smoke-log.txt.'}
    if((Get-FileHash -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskApkHash){throw 'APK changed during verification.'}
    @{apk_sha256=$taskApkHash;passed=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOut 'release-smoke.json') -Encoding utf8
    adb -s $Serial pull /sdcard/Android/data/com.tadpole.instrument/files/release-smoke.txt (Join-Path $taskOut 'release-smoke.txt')
    adb -s $Serial pull /sdcard/Android/data/com.tadpole.instrument/files/release-main.png (Join-Path $taskOut 'release-main.png')
    $taskResult
} finally {Pop-Location}
