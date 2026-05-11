@echo off

echo ============================================
echo  CarPayIn Server Start
echo ============================================
echo.

REM Check Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python not found. Please install Python and add to PATH.
    pause
    exit /b 1
)

REM Install packages
echo [1/5] Installing packages...
python -m pip install fastapi "uvicorn[standard]" httpx paho-mqtt --quiet
echo      Done
echo.

REM MQTT Broker
echo [2/5] MQTT Broker...
where mosquitto >nul 2>&1
if %errorlevel%==0 (
    start "MQTT" cmd /k "mosquitto -v"
    echo      Started
) else (
    echo      Skipped (mosquitto not installed)
)
timeout /t 2 /nobreak >nul

REM Mock PG - port 9000
echo [3/5] Mock PG (port 9000)...
start "Mock PG" cmd /k "cd /d %~dp0mock_pg && python -m uvicorn main:app --host 0.0.0.0 --port 9000 --reload"
timeout /t 2 /nobreak >nul

REM Parking PMS - port 8001
echo [4/5] Parking PMS (port 8001)...
start "Parking PMS" cmd /k "cd /d %~dp0parking_pms && python -m uvicorn main:app --host 0.0.0.0 --port 8001 --reload"
timeout /t 2 /nobreak >nul

REM Main Backend - port 8000  (--host 0.0.0.0 : phone QR scan access)
echo [5/5] Main Backend (port 8000)...
start "Backend" cmd /k "cd /d %~dp0backend_server && python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload"

echo.
echo ============================================
echo  All servers started!
echo.
echo  Backend API : http://localhost:8000/docs
echo  Parking PMS : http://localhost:8001/docs
echo  Mock PG     : http://localhost:9000/docs
echo.
echo  Emulator  : http://10.0.2.2:8000
echo  Phone QR  : http://192.168.201.213:8000
echo ============================================
echo.
pause
