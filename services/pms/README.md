# pms

Mock 주차장 관리 시스템 API입니다.

## 담당 기능

- 차량 사전 등록 요청 저장 (Redis TTL 기반)
- LPR 입차 이벤트 수신 및 웹훅 발행
- 주차 요금 계산
- 결제 완료 통보 수신 및 출차 차단기 개방

## 폴더 구조

```
app/
  api/            FastAPI 라우터·스키마·의존성
  application/    유스케이스 서비스
  infra/          DB·Repository·요금 계산·외부 클라이언트
migrations/       Alembic 마이그레이션
tests/            단위·API·통합 테스트
```

## 실행 명령

컨테이너 시작 명령:

```text
alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 로컬 개발

의존성 설치 및 테스트 실행:

```powershell
pip install -r requirements.txt
python -m pytest tests/unit tests/api -q --import-mode=importlib
```

DB가 실행 중일 때 Docker 없이 서비스 실행:

```powershell
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
```

## 주요 엔드포인트

```
POST /parking/pre-register
POST /lpr/entry
GET  /parking/fee
POST /payment/complete
POST /lpr/exit
```

API 명세 원본: `../../docs/api/car-pay-in-openapi.yaml`

## AWS 배포

```
GitLab CI (main 브랜치)
  └─ build: Docker 이미지 빌드 → AWS ECR 푸시
  └─ deploy: AWS SSM send-command → EC2에서 docker compose up
```

| 컴포넌트 | 구성 |
|---------|------|
| 서버 | EC2 (Ubuntu) + Docker Compose |
| 데이터베이스 | PostgreSQL on EC2 |
| Redis | ElastiCache Redis (pms-redis, port 6380) |
| 원격 배포 | AWS SSM System Manager |

런타임 환경변수는 SSM send-command로 EC2에 `.env` 파일로 전달됩니다.
전체 환경변수 목록: `../../docs/deployment/aws-env.md`
