# Car Pay In

Car Pay In is a monorepo for the in-vehicle parking payment flow. It contains
the Android client, the main backend, mock payment/card services, PMS service,
scenario documents, local Docker Compose, and GitLab Registry deployment tools.

## Repository Layout

```text
services/
  android-app/        Android client used for local and in-car testing
  carpayin-backend/   Main API for auth, vehicle, card, parking, and payment
  mock-card/          Mock card company API
  mock-pg/            Mock PG API and card registration WebView
  pms/                Mock parking management system API
  webots/             Webots vehicle and barrier simulation controllers

docs/
  api/                OpenAPI contract
  DB schemas/         Database and Redis schema documents
  deployment/         Registry, CI/CD, and deployment notes
  diagrams/           Mermaid sequence diagrams
  scenarios/          Scenario flow documents
  use-cases/          Use-case level specifications

scripts/
  build-push-images.ps1      Local GitLab Registry image build/push helper
  start-local-full.ps1       Start Docker services and local support setup
  deploy-from-registry.ps1   Pull registry images and run Docker Compose
  start-local-e2e.ps1        Start local E2E dependencies
  stop-local-e2e.ps1         Stop local E2E dependencies
```

## Documentation Map

- API contract: `docs/api/car-pay-in-openapi.yaml`
- Business flow specs: `docs/use-cases/`
- Presentation/scenario flow: `docs/scenarios/`
- Mermaid sequence sources: `docs/diagrams/`
- DB and Redis schemas: `docs/DB schemas/`
- Testing conventions: `docs/conventions/`
- Android setup: `services/android-app/README.md`

## Local Configuration

Copy `.env.example` to `.env` and fill local secrets before running real
Hyundai OAuth.

Required for the main local flow:

```text
PUBLIC_BASE_URL
HYUNDAI_CLIENT_ID
HYUNDAI_CLIENT_SECRET
PG_PUBLIC_BASE_URL
```

Do not commit `.env` or real credentials.

Android uses `services/android-app/local.properties`. Copy
`services/android-app/local.properties.example` before compiling or launching
the app.

## Local Run

Start the local service stack:

```powershell
docker compose up -d --build
```

Useful ports:

```text
8000  carpayin-backend
8001  pms
8002  mock-pg
8003  mock-card
5432  carpayin-postgres
5433  mock-card-postgres
5434  mock-pg-postgres
5435  pms-postgres
6379  redis
```

API docs are available after the stack starts:

```text
http://localhost:8000/docs       carpayin-backend Swagger UI
http://localhost:8001/docs       PMS Swagger UI
http://localhost:8002/docs       mock-pg Swagger UI
http://localhost:8003/docs       mock-card Swagger UI
```

For a guided local startup script:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local-full.ps1
```

Stop the full local stack:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-local-full.ps1
```

## Tests

Run all Python service unit/API tests:

```powershell
make test
```

Run one backend test set directly:

```powershell
cd services/carpayin-backend
python -m pytest tests/unit tests/api -q --import-mode=importlib
```

Compile the Android app:

```powershell
cd services/android-app
.\gradlew.bat :app:compileDebugKotlin
```

## AWS Infrastructure

| 구성 요소 | 서비스 | 배포 방식 |
|-----------|--------|-----------|
| carpayin-backend | ECS Fargate (Multi-AZ) | GitLab CI → ECR → ECS update-service |
| carpayin DB | RDS PostgreSQL (Multi-AZ, 자동 failover) | - |
| carpayin Redis | ElastiCache Redis | - |
| pms | EC2 + Docker Compose | GitLab CI → ECR → SSM send-command |
| pms DB | PostgreSQL on EC2 | - |
| mock-pg | EC2 + Docker Compose | GitLab CI → ECR → SSM send-command |
| mock-pg DB | PostgreSQL on EC2 | - |
| pms/carpayin Redis | ElastiCache Redis | - |
| 인증 | AWS Cognito | QR OAuth, AAOS 앱 IoT Core Identity Pool |
| 푸시 알림 | SQS → Lambda → IoT Core | 입차 확인 이벤트 → AAOS 앱 MQTT |
| 차단기 시뮬레이션 | Webots (Ubuntu) → IoT Core | MQTT pub/sub |
| 이미지 저장소 | AWS ECR | GitLab CI build stage |
| EC2 원격 배포 | AWS SSM | docker compose pull & up |

## CI/CD

모든 브랜치 push·MR에서 테스트를 실행하고, `main` 브랜치 push 시 서비스 코드
변경분만 빌드해 AWS에 자동 배포합니다.

```
test  → 모든 브랜치: Python unit/API 테스트
build → main + 서비스 코드 변경: Docker 이미지 빌드 → AWS ECR 푸시
deploy
  carpayin-backend → ECS update-service (Fargate 롤링 배포)
  pms              → EC2 SSM send-command (docker compose up)
  mock-pg          → EC2 SSM send-command (docker compose up)
```

GitLab CI/CD 변수 설정 필요:

```text
AWS_REGION
AWS_ACCOUNT_ID         (또는 IAM role로 자동 조회)
ECS_CLUSTER
ECS_SERVICE_CARPAYIN_BACKEND
MOCKPMS_EC2_INSTANCE_ID
MOCKPG_EC2_INSTANCE_ID
MOCKPMS_DATABASE_URL   (Masked)
MOCKPMS_WEBHOOK_TOKEN  (Masked)
MOCKPG_DATABASE_URL    (Masked)
MOCKPG_WEBHOOK_SECRET  (Masked)
```

See `docs/deployment/aws-env.md` for full environment variable reference.
