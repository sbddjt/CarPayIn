@echo off

echo ============================================
echo  CarPayIn Server Stop
echo ============================================
echo.

REM 포트별 프로세스 강제 종료
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

REM 혹시 남아있는 uvicorn / python 프로세스 정리
echo [+] 잔여 uvicorn 프로세스 정리...
taskkill /F /IM uvicorn.exe >nul 2>&1

REM python.exe는 다른 용도로 쓸 수 있으므로 주석 처리
REM taskkill /F /IM python.exe >nul 2>&1

echo.
echo ============================================
echo  모든 서버 종료 완료
echo.
echo  포트 상태 확인:
netstat -ano | findstr "8000\|8001\|9000" | findstr LISTENING
echo  (아무것도 안 뜨면 정상적으로 종료된 것입니다)
echo ============================================
echo.
pause
