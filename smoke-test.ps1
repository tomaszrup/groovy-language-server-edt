$serverDir = "c:\Users\konta\.vscode\extensions\groovy-language-server.groovy-language-server-0.1.0\server"
$tmpWs = "$env:TEMP\gws3"
if (Test-Path $tmpWs) { Remove-Item $tmpWs -Recurse -Force }
New-Item $tmpWs -ItemType Directory -Force | Out-Null

$osgiJar = (Get-ChildItem "$serverDir\plugins\org.eclipse.osgi_*.jar")[0].FullName
Write-Host "OSGi: $osgiJar"

$so = "$env:TEMP\gso.txt"
$se = "$env:TEMP\gse.txt"

$argList = @(
    "-Dosgi.configuration.area=$serverDir\config_win"
    "-Dosgi.bundles.defaultStartLevel=4"
    "-Declipse.product=org.eclipse.groovy.ls.core.product"
    "-Dosgi.install.area=$serverDir"
    "-Dlog.level=ALL"
    "-jar"
    $osgiJar
    "-data"
    $tmpWs
    "-configuration"
    "$serverDir\config_win"
)

$p = Start-Process java -ArgumentList $argList -NoNewWindow -PassThru -RedirectStandardOutput $so -RedirectStandardError $se
Start-Sleep 8

if ($p.HasExited) {
    Write-Host "EXIT CODE: $($p.ExitCode)"
} else {
    Write-Host "RUNNING PID: $($p.Id)"
    Stop-Process $p -Force
}

Write-Host "=== STDERR ==="
if (Test-Path $se) { Get-Content $se -Tail 60 }
Write-Host "=== STDOUT ==="
if (Test-Path $so) { Get-Content $so -Tail 20 }

# Check .metadata log
$logFile = "$tmpWs\.metadata\.log"
if (Test-Path $logFile) {
    Write-Host "=== .metadata .log ==="
    Get-Content $logFile -Tail 80
}
