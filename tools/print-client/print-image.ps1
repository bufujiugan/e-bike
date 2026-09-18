param(
    [Parameter(Mandatory=$true)][string]$ImagePath,
    [Parameter(Mandatory=$true)][string]$PrinterName,
    [Parameter(Mandatory=$true)][int]$WidthMm,
    [Parameter(Mandatory=$true)][int]$HeightMm,
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$labelImage = [System.Drawing.Image]::FromFile($ImagePath)
$document = New-Object System.Drawing.Printing.PrintDocument
try {
    $document.PrinterSettings.PrinterName = $PrinterName
    if (-not $document.PrinterSettings.IsValid) { throw '指定打印机不可用' }
    if ($OutputFile) {
        if ($PrinterName -ne 'Microsoft Print to PDF') { throw '文件输出验收仅支持 Microsoft Print to PDF' }
        $document.PrinterSettings.PrintToFile = $true
        $document.PrinterSettings.PrintFileName = [System.IO.Path]::GetFullPath($OutputFile)
    }
    $document.PrintController = New-Object System.Drawing.Printing.StandardPrintController
    $document.DocumentName = [System.IO.Path]::GetFileNameWithoutExtension($ImagePath)
    $document.DefaultPageSettings.PaperSize = New-Object System.Drawing.Printing.PaperSize('BikeLabel', [int]($WidthMm/25.4*100), [int]($HeightMm/25.4*100))
    $document.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(0,0,0,0)
    $document.add_PrintPage({
        param($sender,$eventArgs)
        $eventArgs.Graphics.DrawImage($labelImage, $eventArgs.PageBounds)
        $eventArgs.HasMorePages = $false
    })
    $document.Print()
    Write-Output 'SUBMITTED'
} finally {
    $document.Dispose()
    $labelImage.Dispose()
}
