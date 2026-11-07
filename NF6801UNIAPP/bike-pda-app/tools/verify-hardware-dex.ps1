param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [string]$BuildTools='F:/android-sdk/build-tools/29.0.2'
)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$folder=Join-Path $project ('unpackage/verify-dex/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
$required=[ordered]@{
    'com.qs.n601.utils.PrintUtils$ReadThread.run'='Lcom/bike/pda/hardware/Qs608Receiver;.readLoop:'
    'com.qs.n601.UpdateModule.preparePrint'='Lcom/bike/pda/hardware/Qs608Hardware;.preparePrint:'
    'com.qs.n601.UpdateModule.scan'='Lcom/bike/pda/hardware/Qs608Hardware;.requestScan:'
    'com.qs.n601.utils.PrintUtils$2.handleMessage'='Lcom/bike/pda/hardware/Qs608Hardware;.onDecodedScan:'
    'com.qs.n601.utils.PrintUtils.printBitmap'='Lcom/bike/pda/hardware/Qs608Hardware;.printBitmap:'
    'com.qs.n601.utils.PrintUtils.send'='Lcom/bike/pda/hardware/Qs608Transport;.send:'
}
$found=@{}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
try {
    foreach($entry in $zip.Entries) {
        if($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
        $path=Join-Path $folder $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$path,$false)
        # 在内存中筛选，避免巨量反汇编逐行经过PowerShell管道拖慢验收。
        $listing=(& (Join-Path $BuildTools 'dexdump.exe') -d $path | Out-String)
        if($LASTEXITCODE -ne 0) { throw 'DEX反汇编失败' }
        $blocks=[regex]::Matches($listing,'(?ms)^[^\r\n]*\|\[[0-9a-f]+\] ([^:\r\n]+):[^\r\n]*\r?\n(.*?)(?=^[^\r\n]*\|\[[0-9a-f]+\]|\z)')
        foreach($block in $blocks) {
            $name=$block.Groups[1].Value
            if($required.Contains($name)) {
                $body=$block.Groups[2].Value
                if($body.Contains($required[$name]) -and $body.Contains('invoke-static')) { $found[$name]=$true }
            }
        }
    }
} finally { $zip.Dispose() }
foreach($name in $required.Keys) {
    if(!$found[$name]) { throw ('缺少实际原生调用：'+$name) }
    Write-Output ('PASS '+$name+' -> '+$required[$name])
}
Write-Output ('DEX extraction: '+$folder)
