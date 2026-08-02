<#
.SYNOPSIS
    Vendors the models.dev model capability data into the repo at build time.

.DESCRIPTION
    Downloads https://models.dev/api.json and emits a slim capabilities file
    containing only the providers Feather Wand supports and only the capability
    fields it consumes (reasoning, effort values, toggle, budget range, vision,
    pdf). models.dev exposes no commit-pinned artifact, so the vendored file
    itself is the reviewable pin: re-run this script (locally or in CI), then
    review the git diff like any other dependency bump.

    Nothing is fetched at runtime: the slim file is committed to git and loaded
    from the classpath by ModelCapabilityCatalog.

.EXAMPLE
    pwsh scripts/Update-ModelCapabilities.ps1
#>
param()

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$url = "https://models.dev/api.json"
$outFile = Join-Path $repoRoot "src\main\resources\org\qainsights\jmeter\ai\reasoning\model-capabilities.json"

Write-Host "Downloading models.dev model data ..."
$tmp = Join-Path $env:TEMP "modelsdev-api.json"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

Write-Host "Trimming to supported providers ..."
$outDir = Split-Path -Parent $outFile
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# The trim runs in Java (Jackson) for robust JSON handling.
$m2 = Join-Path $env:USERPROFILE ".m2\repository\com\fasterxml\jackson\core"
$cp = @(
    (Join-Path $m2 "jackson-databind\2.20.1\jackson-databind-2.20.1.jar"),
    (Join-Path $m2 "jackson-core\2.20.1\jackson-core-2.20.1.jar"),
    (Join-Path $m2 "jackson-annotations\2.20\jackson-annotations-2.20.jar")
) -join ";"
javac -cp $cp -d $env:TEMP (Join-Path $PSScriptRoot "TrimModelCapabilities.java")
java -cp "$cp;$env:TEMP" TrimModelCapabilities $tmp $outFile
if ($LASTEXITCODE -ne 0) { throw "Trim failed" }

# Stamp the fetch timestamp into the vendored file's header fields.
$json = Get-Content $outFile -Raw | ConvertFrom-Json
$json | Add-Member -NotePropertyName fetched -NotePropertyValue ((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")) -Force
$json | ConvertTo-Json -Depth 6 -Compress | Set-Content $outFile -Encoding UTF8

Write-Host "Vendored capabilities -> $outFile"
Remove-Item $tmp -Force -ErrorAction SilentlyContinue
