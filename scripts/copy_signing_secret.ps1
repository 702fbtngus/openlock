param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD', 'KEY_ALIAS')]
    [string]$Name
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$secretPath = Join-Path (Join-Path $workspaceRoot '.signing') "$Name.txt"
if (-not (Test-Path -LiteralPath $secretPath)) {
    throw "Missing local signing value: $Name. Generate the signing key once first."
}
Get-Content -LiteralPath $secretPath -Raw | Set-Clipboard
Write-Output "$Name copied. Paste into the matching GitHub Actions repository secret."
