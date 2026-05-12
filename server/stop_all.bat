@echo off
chcp 65001 > nul

echo ============================================
echo  CarPayIn Server Stop
echo ============================================
echo.

echo [1/3] 포트 8000 (Backend) 종료 중...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8000 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo      PID %%a 종료
)

echo [2/3] 포트 8001 (Parking PMS) 종료 중...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8001 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo      PID %%a 종료
)

echo [3/3] 포트 9000 (Mock PG) 종료 중...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :9000 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
    echo      PID %%a 종료
)

echo.
echo [+] 잔여 uvicorn 프로세스 정리...
taskkill /F /IM uvicorn.exe >nul 2>&1

echo.
echo ============================================
echo  모든 서버 종료 완료
echo.
echo  포트 상태 확인:
netstat -ano | findstr "8000\|8001\|9000" | findstr LISTENING
echo  (아무것도 안 뜨면 정상 종료)
echo ============================================
echo.
pause
