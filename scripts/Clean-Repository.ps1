[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Untrack,
    [switch]$IncludeLocalConfig
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gitDirectory = Join-Path $repositoryRoot ".git"

if (-not (Test-Path -LiteralPath $gitDirectory -PathType Container)) {
    throw "Repository root was not found: $repositoryRoot"
}

$generatedPaths = @(
    ".gradle",
    ".kotlin",
    ".idea",
    "build",
    "app\build"
)

function Assert-PathInsideRepository {
    param([Parameter(Mandatory)][string]$Path)

    $rootWithSeparator = $repositoryRoot.TrimEnd('\') + '\'
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($rootWithSeparator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the repository: $fullPath"
    }
    return $fullPath
}

foreach ($relativePath in $generatedPaths) {
    $targetPath = Assert-PathInsideRepository (Join-Path $repositoryRoot $relativePath)
    if (Test-Path -LiteralPath $targetPath) {
        if ($PSCmdlet.ShouldProcess($targetPath, "Remove generated directory")) {
            Remove-Item -LiteralPath $targetPath -Recurse -Force
        }
    }
}

$localFiles = @("local.properties")
if ($IncludeLocalConfig) {
    foreach ($relativePath in $localFiles) {
        $targetPath = Assert-PathInsideRepository (Join-Path $repositoryRoot $relativePath)
        if (Test-Path -LiteralPath $targetPath -PathType Leaf) {
            if ($PSCmdlet.ShouldProcess($targetPath, "Remove local-only file")) {
                Remove-Item -LiteralPath $targetPath -Force
            }
        }
    }
}

if ($Untrack) {
    $trackedGeneratedPaths = @(
        ".gradle",
        ".kotlin",
        ".idea",
        "build",
        "app/build"
    )
    if ($IncludeLocalConfig) {
        $trackedGeneratedPaths += "local.properties"
    }

    if ($PSCmdlet.ShouldProcess($repositoryRoot, "Remove ignored generated files from the Git index")) {
        Push-Location $repositoryRoot
        try {
            & git rm -r --cached --ignore-unmatch -- $trackedGeneratedPaths
            if ($LASTEXITCODE -ne 0) {
                throw "git rm --cached failed with exit code $LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
    }
}

Write-Host "Repository cleanup complete."
Write-Host "Review changes with: git status --short"
