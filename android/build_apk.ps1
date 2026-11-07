# Clean Release Android APK Build & Sign Script for Quiz Math Assistant
$ErrorActionPreference = "Stop"

$sdk = "C:\Users\THESHMIL\Android\Sdk"
$buildTools = "$sdk\build-tools\34.0.0"
$aapt2 = "$buildTools\aapt2.exe"
$d8 = "$buildTools\d8.bat"
$zipalign = "$buildTools\zipalign.exe"
$apksigner = "$buildTools\apksigner.bat"
$androidJar = "$sdk\platforms\android-34\android.jar"
$keystore = "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\release.jks"

$jdk = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin"
$javac = "$jdk\javac.exe"
$jar = "$jdk\jar.exe"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$projectRoot = Split-Path -Parent $PSScriptRoot
$androidDir = "$projectRoot\android"
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

Write-Host ">>> [1/7] Cleaning build cache..."
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
New-Item -ItemType Directory -Path $genDir -Force | Out-Null
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
New-Item -ItemType Directory -Path $dexDir -Force | Out-Null

Write-Host ">>> [2/7] Compiling resources with aapt2..."
& $aapt2 compile --dir $resDir -o $compiledRes

Write-Host ">>> [3/7] Linking manifest & generating R.java..."
& $aapt2 link -I $androidJar --manifest $manifest --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name "1.0" --java $genDir -o $unalignedApk --auto-add-overlay $compiledRes

Write-Host ">>> [4/7] Compiling Java sources with JDK 17..."
$javaFiles = @(
    "$genDir\com\quizearn\assistant\R.java",
    "$androidDir\src\main\java\com\quizearn\assistant\MainActivity.java",
    "$androidDir\src\main\java\com\quizearn\assistant\FloatingOverlayService.java",
    "$androidDir\src\main\java\com\quizearn\assistant\QuizEarnAccessibilityService.java"
)
& $javac -cp $androidJar -d $classesDir -source 17 -target 17 @javaFiles

Write-Host ">>> [5/7] Converting bytecode to classes.dex with D8..."
$allClassFiles = (Get-ChildItem -Path $classesDir -Recurse -Filter "*.class").FullName
& cmd /c "`"$d8`" --output `"$dexDir`" --lib `"$androidJar`" $($allClassFiles -join ' ')"

Write-Host ">>> [6/7] Packaging and aligning APK..."
Push-Location $dexDir
& $jar -uf $unalignedApk classes.dex
Pop-Location
& $zipalign 4 $unalignedApk $alignedApk

Write-Host ">>> [7/7] Signing APK with Release Certificate (v1, v2, v3 schemes)..."
& cmd /c "`"$apksigner`" sign --ks `"$keystore`" --ks-pass pass:QuizEarn2026Safe --key-pass pass:QuizEarn2026Safe --ks-key-alias quizassistant --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$finalApk`" `"$alignedApk`""

Write-Host "`n>>> [SUCCESS] Release APK built and signed successfully!"
Get-Item $finalApk | Select-Object Name, Length, LastWriteTime, FullName | Format-List
