@echo off
setlocal
chcp 65001 > nul

set BACKEND_PORT=8002
set LEGACY_BACKEND_PORT=8000
set PMS_PORT=8001
set MOCK_PG_PORT=9000

echo ============================================
echo  CarPayIn Stop
echo ============================================
echo.

echo [1/5] Stopping backend ports...
call :kill_port %BACKEND_PORT% "backend"
call :kill_port %LEGACY_BACKEND_PORT% "old backend"

echo [2/5] Stopping Parking PMS...
call :kill_port %PMS_PORT% "parking pms"

echo [3/5] Stopping Mock PG...
call :kill_port %MOCK_PG_PORT% "mock pg"

echo [4/5] Stopping ngrok...
taskkill /F /T /IM ngrok.exe > nul 2>&1

echo [5/5] Closing CarPayIn server windows...
call :kill_window "CarPayIn-Backend*"
call :kill_window "CarPayIn-PMS*"
call :kill_window "CarPayIn-MockPG*"
call :kill_window "CarPayIn-ngrok*"

echo.
echo Remaining listeners on 8000/8001/8002/9000:
netstat -ano | findstr "8000 8001 8002 9000" | findstr "LISTENING"
if errorlevel 1 echo      none

echo.
echo ============================================
echo  Stopped
echo ============================================
echo.
pause
exit /b 0

:kill_port
set PORT=%~1
set NAME=%~2
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT%" ^| findstr "LISTENING"') do (
    taskkill /F /T /PID %%a > nul 2>&1
    echo      stopped %NAME% PID %%a on port %PORT%
)
exit /b 0

:kill_window
taskkill /F /T /FI "WINDOWTITLE eq %~1" > nul 2>&1
exit /b 0
