param(
    [ValidateSet("debug", "release", "test", "all")]
    [string]$Target = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 从 local.properties 读 sdk.dir；读不到就用常见路径兜底
$sdk = $null
$localProps = Join-Path $root "local.properties"
if (Test-Path $localProps) {
    foreach ($line in Get-Content $localProps -Encoding UTF8) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
            $sdk = $Matches[1].Trim().Replace('\:', ':').Replace('\\', '\')
        }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    foreach ($candidate in @("$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\android-sdk", "C:\android-sdk")) {
        if (Test-Path $candidate) { $sdk = $candidate; break }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    throw "找不到 Android SDK：请在 local.properties 里写 sdk.dir=<SDK 路径>"
}
$env:ANDROID_HOME = $sdk
Write-Host "ANDROID_HOME = $sdk"

function Invoke-Gradle([string[]]$Tasks) {
    # gradle 会把告警写到 stderr，Stop 模式下会被当成 NativeCommandError 直接中断，这里放宽一档
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & "$root\gradlew.bat" @Tasks --no-daemon --console=plain 2>&1 | ForEach-Object { Write-Host $_ }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) { throw "gradle 失败，退出码 $code" }
}

switch ($Target) {
    "debug"   { Invoke-Gradle @(":app:assembleDebug") }
    "test"    { Invoke-Gradle @(":app:testDebugUnitTest") }
    "release" { Invoke-Gradle @(":app:testReleaseUnitTest", ":app:assembleRelease") }
    "all"     { Invoke-Gradle @(":app:testDebugUnitTest", ":app:assembleRelease") }
}

# 把产物收拢到 dist/，文件名带版本号，方便直接发 Release
$version = "1.0.0"
$gradleFile = Join-Path $root "app\build.gradle.kts"
if (Test-Path $gradleFile) {
    $m = Select-String -Path $gradleFile -Pattern 'val appVersionName = "([^"]+)"'
    if ($m) { $version = $m.Matches[0].Groups[1].Value }
}

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

if ($Target -ne "test") {
    $src = if ($Target -eq "debug") { "app\build\outputs\apk\debug\app-debug.apk" } else { "app\build\outputs\apk\release\app-release.apk" }
    $full = Join-Path $root $src
    if (Test-Path $full) {
        $name = "dns-pool-$version.apk"
        Copy-Item $full (Join-Path $dist $name) -Force
        Write-Host "产物：dist\$name"
    } else {
        Write-Warning "没找到 $src"
    }
}
