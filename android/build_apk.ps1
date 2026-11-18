$ErrorActionPreference = "Stop"

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path $sdk)) { $sdk = "C:\Users\THESHMIL\Android\Sdk" }
$buildTools = "$sdk\build-tools\34.0.0"
$aapt2 = "$buildTools\aapt2.exe"
$d8 = "$buildTools\d8.bat"
$zipalign = "$buildTools\zipalign.exe"
$apksigner = "$buildTools\apksigner.bat"
$androidJar = "$sdk\platforms\android-34\android.jar"
$projectRoot = Split-Path -Parent $PSScriptRoot
$androidDir = "$projectRoot\android"
$keystore = "$androidDir\release.jks"

$resDir = "$androidDir\src\main\res"
$manifest = "$androidDir\src\main\AndroidManifest.xml"
$buildDir = "$androidDir\build"
$compiledRes = "$buildDir\resources.zip"
$genDir = "$buildDir\gen"
$classesDir = "$buildDir\classes"
$dexDir = "$buildDir\dex"
$unalignedApk = "$buildDir\unaligned.apk"
$alignedApk = "$buildDir\aligned.apk"
$finalApk = "$projectRoot\QuizEarnBot.apk"

if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $buildDir, $genDir, $classesDir, $dexDir -Force | Out-Null

& $aapt2 compile --dir $resDir -o $compiledRes
& $aapt2 link -I $androidJar --manifest $manifest --min-sdk-version 24 --target-sdk-version 34 --version-code 2 --version-name "2.0" --java $genDir -o $unalignedApk --auto-add-overlay $compiledRes

$javaFiles = @("$genDir\com\quizearn\assistant\R.java") + (Get-ChildItem -Path "$androidDir\src\main\java" -Recurse -Filter "*.java").FullName
& javac -encoding UTF-8 -cp $androidJar -d $classesDir -source 17 -target 17 @javaFiles

$allClassFiles = (Get-ChildItem -Path $classesDir -Recurse -Filter "*.class").FullName
& cmd /c "`"$d8`" --min-api 24 --output `"$dexDir`" --lib `"$androidJar`" $($allClassFiles -join ' ')"

Push-Location $dexDir
& jar -uf $unalignedApk classes.dex
Pop-Location
& $zipalign 4 $unalignedApk $alignedApk
& cmd /c "`"$apksigner`" sign --ks `"$keystore`" --ks-pass pass:QuizEarn2026Safe --key-pass pass:QuizEarn2026Safe --ks-key-alias quizassistant --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$finalApk`" `"$alignedApk`""

Write-Host ">>> [SUCCESS] QuizEarnBot v2.0 APK built at $finalApk"