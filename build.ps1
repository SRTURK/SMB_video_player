$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
if(-not $env:JAVA_HOME){
 $localJdk=Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
 if($localJdk){$env:JAVA_HOME=$localJdk.FullName}
}
if(-not $env:ANDROID_HOME){$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
Push-Location $projectRoot
try{
 $tasks=@(if($args.Count -gt 0){$args}else{@(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug')})
 # Reuse an installed copy of the exact wrapper version on this computer.
 $gradleCache=Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-9.4.1-bin'
 $cachedGradle=Get-ChildItem -LiteralPath $gradleCache -Filter gradle.bat -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
 if($cachedGradle){& $cachedGradle.FullName --no-daemon @tasks}else{& .\gradlew.bat --no-daemon @tasks}
 if($LASTEXITCODE -ne 0){throw "Gradle exited with $LASTEXITCODE"}
}finally{Pop-Location}
