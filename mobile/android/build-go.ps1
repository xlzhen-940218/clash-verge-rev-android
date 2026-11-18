# Build libclash.so and libbridge.so for all Android ABIs
# Usage: .\build-go.ps1 [-Abis arm64-v8a,armeabi-v7a,x86,x86_64]

param(
    [string[]]$Abis = @("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
)

$ErrorActionPreference = "Stop"

$ProjectRoot = "c:\Users\xiong\Documents\GitHub\clash-verge-rev\mobile\android"
$CoreDir = "$ProjectRoot\cmfa\core"
$GoMainDir = "$CoreDir\src\main\golang"
$CppDir = "$CoreDir\src\main\cpp"
$GoNativeDir = "$CoreDir\src\main\golang\native"
$BuildDir = "$CoreDir\build\go"
$JniLibsDir = "$CoreDir\src\main\jniLibs"

# Go toolchain
$env:GOROOT = "C:\Users\xiong\go-sdk\go"
$env:GOPATH = "C:\Users\xiong\go"
$env:PATH = "$env:GOROOT\bin;$env:GOPATH\bin;$env:PATH"
$env:GOPROXY = "https://goproxy.cn,direct"

# NDK
$NdkDir = "C:\Users\xiong\AppData\Local\Android\Sdk\ndk\28.2.13676358"
$ClangDir = "$NdkDir\toolchains\llvm\prebuilt\windows-x86_64\bin"

$AbiConfig = @{
    "arm64-v8a"    = @{ Arch = "arm64";  Clang = "aarch64-linux-android24-clang.cmd";    Target = "aarch64-linux-android24" }
    "armeabi-v7a"  = @{ Arch = "arm";    Clang = "armv7a-linux-androideabi24-clang.cmd"; Target = "armv7a-linux-androideabi24" }
    "x86"          = @{ Arch = "386";    Clang = "i686-linux-android24-clang.cmd";       Target = "i686-linux-android24" }
    "x86_64"       = @{ Arch = "amd64";  Clang = "x86_64-linux-android24-clang.cmd";     Target = "x86_64-linux-android24" }
}

Write-Host "=== Building Go native libraries ===" -ForegroundColor Cyan

foreach ($abi in $Abis) {
    $cfg = $AbiConfig[$abi]
    $outDir = "$BuildDir\$abi"
    New-Item -ItemType Directory -Force $outDir | Out-Null

    Write-Host "`n[1/2] Compiling Go -> libclash.so ($abi)..." -ForegroundColor Yellow
    Push-Location $GoMainDir
    $env:GOOS = "android"
    $env:GOARCH = $cfg.Arch
    $env:CGO_ENABLED = "1"
    $env:CC = "$ClangDir\$($cfg.Clang)"
    go build -tags "foss with_gvisor cmfa" -buildmode=c-shared -o "$outDir\libclash.so" ./native
    Pop-Location

    $clashSize = [math]::Round((Get-Item "$outDir\libclash.so").Length / 1MB, 2)
    Write-Host "      libclash.so: $clashSize MB" -ForegroundColor Green

    Write-Host "[2/2] Compiling C -> libbridge.so ($abi)..." -ForegroundColor Yellow
    & "$ClangDir\clang.exe" `
        --target=$($cfg.Target) `
        -shared -fPIC `
        -o "$outDir\libbridge.so" `
        "$CppDir\main.c" "$CppDir\jni_helper.c" "$CppDir\bridge_helper.c" `
        "-I$CppDir" "-I$outDir" "-I$GoNativeDir" `
        "-L$outDir" -lclash -llog

    $bridgeSize = [math]::Round((Get-Item "$outDir\libbridge.so").Length / 1KB, 2)
    Write-Host "      libbridge.so: $bridgeSize KB" -ForegroundColor Green

    # Copy to jniLibs
    $destDir = "$JniLibsDir\$abi"
    New-Item -ItemType Directory -Force $destDir | Out-Null
    Copy-Item "$outDir\libclash.so" "$destDir\libclash.so" -Force
    Copy-Item "$outDir\libbridge.so" "$destDir\libbridge.so" -Force
}

Write-Host "`n=== All ABIs built successfully ===" -ForegroundColor Cyan
Write-Host "Output: $JniLibsDir"
