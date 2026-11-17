@echo off
rem ============================================================
rem build-containers.bat - Launcher cho build-containers.ps1 (Windows 10/11)
rem
rem   build-containers.bat                    hien menu
rem   build-containers.bat init               tao be-web-admin\.env (lan dau)
rem   build-containers.bat up                 build va chay ca 5 container
rem   build-containers.bat up -PublicHost 192.168.1.10
rem   build-containers.bat logs vue-web
rem
rem Cac lenh: init up build down clean restart ps logs apk doctor fix-eol help
rem
rem Goi PowerShell voi -ExecutionPolicy Bypass chi cho lan chay nay, nen khong can
rem sua Execution Policy cua may. File nay cố tinh khong dung nhan/goto de chay dung
rem ca khi bi doi sang xuong dong LF.
rem ============================================================
setlocal
set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" set "PS_EXE=powershell.exe"
"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-containers.ps1" %*
exit /b %errorlevel%
