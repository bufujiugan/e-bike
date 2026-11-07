$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$destination = Join-Path $PSScriptRoot '../static/icons'
if (!(Test-Path -LiteralPath $destination)) { New-Item -ItemType Directory -Path $destination | Out-Null }
foreach ($size in @(48, 72, 96, 144, 192, 512)) {
    $bitmap = New-Object Drawing.Bitmap($size, $size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $pen = New-Object Drawing.Pen([Drawing.Color]::White, 7)
    try {
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#0f766e'))
        $graphics.ScaleTransform($size / 128.0, $size / 128.0)
        $pen.StartCap = [Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
        $graphics.DrawEllipse($pen, 13, 64, 38, 38)
        $graphics.DrawEllipse($pen, 77, 64, 38, 38)
        foreach ($line in @(@(32,83,55,44), @(55,44,79,83), @(79,83,32,83), @(32,83,51,52), @(51,52,85,52), @(85,52,96,83), @(47,36,63,36), @(78,36,89,36), @(89,36,85,52))) {
            $graphics.DrawLine($pen, [single]$line[0], [single]$line[1], [single]$line[2], [single]$line[3])
        }
        $bitmap.Save((Join-Path $destination "bike-$size.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
Write-Output '已生成 Android 图标。'
