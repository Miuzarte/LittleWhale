# Rebuild the OCR models the APK ships
#
# Two files come out, both under app/src/main/assets/ocr (gitignored, they are 6 MiB of build
# product): PP-OCRv6 tiny's detector and recognizer, straight from HuggingFace with nothing
# changed but their shapes.
#
# Why so little: the official ONNX exports have every spatial dimension dynamic
# (`DynamicDimension.0`) and QNN refuses dynamic shapes outright, so the only edit needed is to
# pin them (640x640 for det, 48x320 for rec). Everything else is left alone on purpose —
# see docs/step8-record.md for the two routes that were tried and what each cost.
#
# usage: pwsh -File tools/ocr/build-models.ps1
param(
    # Interpreter with `onnx` in it
    [string]$Python = 'B:\Git\LittleWhale\build\ocr-spike\.venv\Scripts\python.exe',
    [string]$Work = 'B:\Git\LittleWhale\build\ocr-work',
    [string]$Out = 'B:\Git\LittleWhale\app\src\main\assets\ocr'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Python)) { throw "no interpreter at $Python" }

$models = @(
    @{ name = 'det'; kind = 'det'; repo = 'PaddlePaddle/PP-OCRv6_tiny_det_onnx' },
    @{ name = 'rec'; kind = 'rec'; repo = 'PaddlePaddle/PP-OCRv6_tiny_rec_onnx' }
)

New-Item -ItemType Directory -Force -Path $Work, $Out | Out-Null
$rewrite = Join-Path $PSScriptRoot 'rewrite_onnx.py'

foreach ($model in $models) {
    $name = $model.name
    $raw = Join-Path $Work "$name.onnx"
    $pinned = Join-Path $Out "$name.onnx"
    Write-Host "=== $name"
    if (-not (Test-Path $raw)) {
        $url = "https://huggingface.co/$($model.repo)/resolve/main/inference.onnx"
        Write-Host "  fetching $url"
        curl.exe -sL $url -o $raw
    }
    & $Python $rewrite --kind $model.kind --input $raw --output $pinned
    if ($LASTEXITCODE -ne 0) { throw "pinning failed for $name" }
}

Get-ChildItem $Out | ForEach-Object { "{0,10:N0}  {1}" -f $_.Length, $_.Name }
Write-Host "models written to $Out"
