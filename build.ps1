param([string]$JavaHome = $env:JAVA_HOME, [string]$AndroidSdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw '请设置 JAVA_HOME 或传入 -JavaHome，要求 Java 17。' }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { throw '请设置 ANDROID_HOME 或传入 -AndroidSdk。' }
$javaExecutable = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw '未找到 Java。' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
& $javaExecutable '-Dfile.encoding=UTF-8' -classpath (Join-Path $projectPath 'gradle/wrapper/gradle-wrapper.jar') org.gradle.wrapper.GradleWrapperMain -p $projectPath ':core:test' ':app:assembleDebug' ':app:lintDebug' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw '构建或检查失败。' }
