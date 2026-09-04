param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [string]$ExpectedCertificateSha256
)
$ErrorActionPreference = 'Stop'
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$buildTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android build-tools not found' }
$signature = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk 2>&1
$signatureExit = $LASTEXITCODE
$signature | Where-Object { "$_" -notmatch '^WARNING:' } | Write-Output
if ($signatureExit -ne 0) { throw "APK signature verification failed (exit $signatureExit)" }
if (-not ($signature -match 'Verified using v2 scheme .*: true')) { throw 'APK v2 signature missing' }
if (-not ($signature -match 'Verified using v3 scheme .*: true')) { throw 'APK v3 signature missing' }
if ($ExpectedCertificateSha256 -and -not ($signature -match "certificate SHA-256 digest: $ExpectedCertificateSha256")) {
    throw 'Signing certificate changed'
}
& (Join-Path $buildTools.FullName 'zipalign.exe') -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed' }
$badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK manifest parsing failed' }
if ($badging -match '^application-debuggable') { throw 'Release APK is debuggable' }
$badging | Where-Object { $_ -match '^package:|^sdkVersion:|^targetSdkVersion:|^application-label:|^native-code:' }
Write-Output 'APK_SIGNATURE_ALIGNMENT_MANIFEST=PASS'
Write-Output ('APK_SHA256=' + (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant())
