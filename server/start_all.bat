@echo off
setlocal
chcp 65001 > nul

set BACKEND_PORT=8002
set LEGACY_BACKEND_PORT=8000
set PMS_PORT=8001
set MOCK_PG_PORT=9000
set NGROK_URL=https://pretext-armless-wieldable.ngrok-free.dev
set BACKEND_URL=http://localhost:%BACKEND_PORT%

echo ============================================
echo  CarPayIn Start
echo ============================================
echo.
echo  Backend : http://localhost:%BACKEND_PORT%
echo  PMS     : http://localhost:%PMS_PORT%
echo  Mock PG : http://localhost:%MOCK_PG_PORT%
echo  ngrok   : %NGROK_URL%  -^> localhost:%BACKEND_PORT%
echo.

echo [0/4] Cleaning old processes...
call :kill_port %LEGACY_BACKEND_PORT% "old backend"
call :kill_port %BACKEND_PORT% "backend"
call :kill_port %PMS_PORT% "parking pms"
call :kill_port %MOCK_PG_PORT% "mock pg"
taskkill /F /T /IM ngrok.exe > nul 2>&1
call :kill_window "CarPayIn-Backend*"
call :kill_window "CarPayIn-PMS*"
call :kill_window "CarPayIn-MockPG*"
call :kill_window "CarPayIn-ngrok*"
timeout /t 1 /nobreak > nul

echo [1/4] Starting backend on port %BACKEND_PORT%...
start "CarPayIn-Backend 8002" /D "%~dp0backend" cmd /k "uvicorn main:app --host 0.0.0.0 --port %BACKEND_PORT% --reload"
timeout /t 2 /nobreak > nul

echo [2/4] Starting Parking PMS on port %PMS_PORT%...
start "CarPayIn-PMS 8001" /D "%~dp0parking_pms" cmd /k "set BACKEND_URL=%BACKEND_URL%&& uvicorn main:app --host 0.0.0.0 --port %PMS_PORT% --reload"
timeout /t 2 /nobreak > nul

echo [3/4] Starting Mock PG on port %MOCK_PG_PORT%...
start "CarPayIn-MockPG 9000" /D "%~dp0mock_pg" cmd /k "set BACKEND_URL=%BACKEND_URL%&& uvicorn main:app --host 0.0.0.0 --port %MOCK_PG_PORT% --reload"
timeout /t 2 /nobreak > nul

echo [4/4] Starting ngrok tunnel...
start "CarPayIn-ngrok 8002" /D "%~dp0" cmd /k "ngrok http %BACKEND_PORT% --url %NGROK_URL%"
timeout /t 3 /nobreak > nul

echo.
echo ============================================
echo  Started
echo.
echo  Backend docs : http://localhost:%BACKEND_PORT%/docs
echo  PMS docs     : http://localhost:%PMS_PORT%/docs
echo  Mock PG docs : http://localhost:%MOCK_PG_PORT%/docs
echo  Public URL   : %NGROK_URL%
echo.
echo  Check backend build:
echo    powershell -Command "(Invoke-WebRequest -UseBasicParsing http://localhost:%BACKEND_PORT%/).Content"
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
