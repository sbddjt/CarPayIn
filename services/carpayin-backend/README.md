# carpayin-backend

Car Pay-in 메인 API 서비스입니다.

## 담당 기능

- QR 로그인 세션 생성 및 상태 폴링
- 현대차 OAuth 콜백 처리
- 차량 확인 및 등록
- 카드 등록 주문 생성 및 PG 웹훅 처리
- 주차 사전 알림 등록 및 PMS 입차 웹훅 처리
- 주차 요금 조회, 빌링키 결제, PMS 결제 완료 통보

## 폴더 구조

```
app/
  api/            FastAPI 라우터·스키마·의존성
  application/    유스케이스 서비스
  domain/         도메인 개념·에러
  infra/          DB·Redis·보안·외부 클라이언트
migrations/       Alembic 마이그레이션
tests/
  unit/           유스케이스·클라이언트 단위 테스트
  api/            HTTP 라우트 테스트
  integration/    Repository·Redis 통합 테스트
```

## 실행 명령

컨테이너 시작 명령:

```text
alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000
```

환경변수는 루트 `docker-compose.yaml`과 `.env.example`에 정의되어 있습니다.

## 로컬 개발

의존성 설치 및 테스트 실행:

```powershell
pip install -r requirements.txt
python -m pytest tests/unit tests/api -q --import-mode=importlib
```

로컬 Postgres·Redis가 실행 중일 때 Docker 없이 서비스 실행:

```powershell
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## 주요 엔드포인트

```
POST /auth/qr-session
GET  /auth/hyundai/start
GET  /auth/redirect
GET  /auth/session/{session_id}/status
POST /auth/confirm-car
POST /auth/refresh
POST /card/order
POST /card/webhook
GET  /parking/lots
POST /parking/navigate
POST /webhook/entry
GET  /fee/{session_id}
POST /payment
```

API 명세 원본: `../../docs/api/car-pay-in-openapi.yaml`

## AWS 배포

```
GitLab CI (main 브랜치)
  └─ build: Docker 이미지 빌드 → AWS ECR 푸시
  └─ deploy: aws ecs update-service --force-new-deployment
```

| 컴포넌트 | AWS 서비스 |
|---------|-----------|
| 컨테이너 실행 | ECS Fargate (Multi-AZ) |
| 데이터베이스 | RDS PostgreSQL (Multi-AZ, 자동 failover) |
| 캐시 / 세션 | ElastiCache Redis |
| OAuth 인증 | AWS Cognito |
| 입차 이벤트 발행 | SQS → Lambda → IoT Core |

런타임 환경변수는 ECS task definition secrets로 주입합니다.
전체 환경변수 목록: `../../docs/deployment/aws-env.md`
