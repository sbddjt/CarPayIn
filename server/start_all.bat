@echo off
chcp 65001 > nul

echo ============================================
echo  CarPayIn Server Start
echo ============================================
echo.
echo  [구조]
echo  backend/      - CarPayIn 메인 백엔드     (port 8000, AWS EC2 역할)
echo  parking_pms/  - 아이파킹 PMS 현장 서버   (port 8001, 엣지 역할)
echo  mock_pg/      - Mock 결제 PG              (port 9000, OpenStack 역할)
echo  webots/       - Webots 시뮬레이터          (vehicle/barrier 컨트롤러)
echo.

REM ── 1. 백엔드 (AWS EC2 역할) ───────────────────────────────────────────
echo [1/3] 백엔드 서버 시작 (port 8000)...
cd /d "%~dp0backend"
start "CarPayIn-Backend" cmd /k "uvicorn main:app --host 0.0.0.0 --port 8000 --reload"
cd /d "%~dp0"
timeout /t 2 /nobreak > nul

REM ── 2. 주차장 PMS (엣지 역할) ─────────────────────────────────────────
echo [2/3] 주차장 PMS 시작 (port 8001)...
cd /d "%~dp0parking_pms"
start "CarPayIn-PMS" cmd /k "uvicorn main:app --host 0.0.0.0 --port 8001 --reload"
cd /d "%~dp0"
timeout /t 2 /nobreak > nul

REM ── 3. Mock PG (OpenStack 역할) ──────────────────────────────────────
echo [3/3] Mock PG 서버 시작 (port 9000)...
cd /d "%~dp0mock_pg"
start "CarPayIn-MockPG" cmd /k "uvicorn main:app --host 0.0.0.0 --port 9000 --reload"
cd /d "%~dp0"

echo.
echo ============================================
echo  서버 시작 완료
echo.
echo  백엔드  : http://localhost:8000/docs
echo  PMS     : http://localhost:8001/docs
echo  Mock PG : http://localhost:9000/docs
echo ============================================
echo.
pause
