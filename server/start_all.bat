@echo off

echo ============================================
echo  CarPayIn Server Start
echo ============================================
echo.
echo  [구조]
echo  backend/      - CarPayIn 메인 백엔드    (port 8000, AWS EC2 역할)
echo  parking_pms/  - 아이파킹 PMS 현장 서버  (port 8001, 엣지 역할)
echo  mock_pg/      - Mock 결제 PG            (port 9000, OpenStack 역할)
echo  webots/       - Webots 시뮬레이터        (vehicle/barrier 컨트롤러)
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

REM MQTT Broker (mosquitto) — 차단기 MQTT 통신에 필요
echo [2/5] MQTT Broker...
where mosquitto >nul 2>&1
if %errorlevel%==0 (
    start "MQTT Broker" cmd /k "mosquitto -v"
    echo      Started (localhost:1883)
) else (
    echo      Skipped ^(mosquitto 미설치 — MQTT 알림/차단기 비활성화^)
    echo      설치: https://mosquitto.org/download/
)
timeout /t 2 /nobreak >nul

REM Mock PG — 카드 등록 및 결제 승인 (OpenStack 카드망 모의)
echo [3/5] Mock PG (port 9000)...
start "Mock PG" cmd /k "cd /d %~dp0mock_pg && python -m uvicorn main:app --host 0.0.0.0 --port 9000 --reload"
timeout /t 2 /nobreak >nul

REM Parking PMS — 아이파킹 현장 서버 (LPR + 차단기 + 번호판 관리)
echo [4/5] Parking PMS (port 8001)...
start "Parking PMS" cmd /k "cd /d %~dp0parking_pms && python -m uvicorn main:app --host 0.0.0.0 --port 8001 --reload"
timeout /t 2 /nobreak >nul

REM CarPayIn Backend — 메인 백엔드 (OAuth + 결제 + 세션, --host 0.0.0.0: 폰 QR 스캔 접근)
echo [5/5] CarPayIn Backend (port 8000)...
start "CarPayIn Backend" cmd /k "cd /d %~dp0backend && python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload"

echo.
echo ============================================
echo  All servers started!
echo.
echo  [API Docs]
echo  Backend API : http://localhost:8000/docs
echo  Parking PMS : http://localhost:8001/docs
echo  Mock PG     : http://localhost:9000/docs
echo.
echo  [Android 접근 주소]
echo  Emulator    : http://10.0.2.2:8000
echo  Phone QR    : http://<내 IP>:8000
echo.
echo  [Webots 컨트롤러 경로]
echo  차량 컨트롤러  : server/webots/vehicle_controller.py
echo  차단기 컨트롤러: server/webots/barrier_controller.py
echo ============================================
echo.
pause
