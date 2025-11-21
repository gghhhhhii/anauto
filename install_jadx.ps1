# 下载并安装 jadx
$jadxUrl = "https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip"
$jadxZip = "$env:TEMP\jadx.zip"
$jadxDir = "C:\jadx"

Write-Host "正在下载 jadx..." -ForegroundColor Yellow
Invoke-WebRequest -Uri $jadxUrl -OutFile $jadxZip

Write-Host "正在解压 jadx..." -ForegroundColor Yellow
Expand-Archive -Path $jadxZip -DestinationPath $jadxDir -Force

Write-Host "✓ jadx 已安装到: $jadxDir" -ForegroundColor Green
Write-Host "  可执行文件: $jadxDir\bin\jadx.bat" -ForegroundColor Green
Write-Host ""
Write-Host "运行以下命令反编译参考应用：" -ForegroundColor Cyan
Write-Host "C:\jadx\bin\jadx.bat -d C:\Users\admin.LAPTOP\Desktop\anauto\autobot_decompiled C:\Users\admin.LAPTOP\Desktop\anauto\autobot_3.2.1.apk" -ForegroundColor White

