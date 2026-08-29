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
$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "modelsdev-$PID-$([guid]::NewGuid().ToString('N'))"
$tmp = Join-Path $tmpDir "api.json"
$classpathFile = Join-Path $tmpDir "classpath.txt"
$classesDir = Join-Path $tmpDir "classes"

New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

try {
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

    Write-Host "Resolving Java dependencies from pom.xml ..."
    Push-Location $repoRoot
    try {
        & mvn --batch-mode --no-transfer-progress dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0) { throw "Maven dependency resolution failed" }
    } finally {
        Pop-Location
    }

    $cp = (Get-Content $classpathFile -Raw).Trim()
    if (-not $cp) { throw "Maven produced an empty Java classpath" }

    Write-Host "Trimming to supported providers ..."
    $outDir = Split-Path -Parent $outFile
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # The trim runs in Java (Jackson) for robust JSON handling. Maven owns all
    # dependency versions so this also works on a clean CI runner.
    & javac -cp $cp -d $classesDir (Join-Path $PSScriptRoot "TrimModelCapabilities.java")
    if ($LASTEXITCODE -ne 0) { throw "Trim compilation failed" }

    $runtimeCp = "$cp$([System.IO.Path]::PathSeparator)$classesDir"
    & java -cp $runtimeCp TrimModelCapabilities $tmp $outFile
    if ($LASTEXITCODE -ne 0) { throw "Trim failed" }

    Write-Host "Vendored capabilities -> $outFile"
} finally {
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
