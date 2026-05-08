@echo off
REM CarPayIn 백엔드 전체 서버 시작 스크립트
REM 실행 전: pip install fastapi uvicorn httpx paho-mqtt

echo ============================================
echo  CarPayIn 서버 시작
echo ============================================
echo.

REM 1) Mosquitto MQTT 브로커 (설치된 경우)
echo [1/4] MQTT 브로커 시작...
start "MQTT Broker" cmd /k "mosquitto -v"
timeout /t 2 /nobreak >nul

REM 2) Mock PG 서버 (포트 9000)
echo [2/4] Mock PG 서버 시작 (포트 9000)...
start "Mock PG" cmd /k "cd /d %~dp0mock_pg && uvicorn main:app --port 9000 --reload"
timeout /t 2 /nobreak >nul

REM 3) 아이파킹 Mock PMS (포트 8001)
echo [3/4] 아이파킹 Mock PMS 시작 (포트 8001)...
start "Parking PMS" cmd /k "cd /d %~dp0parking_pms && uvicorn main:app --port 8001 --reload"
timeout /t 2 /nobreak >nul

REM 4) 메인 백엔드 서버 (포트 8000)
echo [4/4] CarPayIn 백엔드 시작 (포트 8000)...
start "Backend" cmd /k "cd /d %~dp0backend_server && uvicorn main:app --port 8000 --reload"

echo.
echo ============================================
echo  모든 서버 시작 완료!
echo  - 백엔드:   http://localhost:8000/docs
echo  - PMS:      http://localhost:8001/docs
echo  - Mock PG:  http://localhost:9000/docs
echo  - MQTT:     localhost:1883
echo ============================================
echo.
echo Pleos Connect 에뮬레이터에서 앱 실행 후
echo http://10.0.2.2:8000 으로 연결됩니다.
pause
