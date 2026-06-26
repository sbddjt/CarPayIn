# Car Pay-in

차량 내 주차 결제 시스템 (In-Vehicle Parking Payment System)

AAOS(Android Automotive OS) 기반 차량 앱에서 QR 로그인 → 차량 확인 → 카드 등록 → 입차 → 요금 결제 → 출차까지 전 과정을 처리하는 서비스입니다.

---

## 서비스 구성

| 서비스 | 역할 | AWS 배포 방식 |
|--------|------|--------------|
| `carpayin-backend` | 메인 API (인증/차량/카드/주차/결제) | ECS Fargate (Multi-AZ) |
| `pms` | Mock 주차장 관리 시스템 | EC2 + Docker Compose |
| `mock-pg` | Mock 결제대행사 | EC2 + Docker Compose |
| `mock-card` | Mock 카드사 | EC2 + Docker Compose |
| `android-app` | AAOS 차량 앱 (Kotlin) | - |
| `webots` | 차단기 시뮬레이션 (Ubuntu) | MQTT → AWS IoT Core |

---

## AWS 인프라

| AWS 서비스 | 용도 |
|-----------|------|
| ECS Fargate | carpayin-backend 컨테이너 실행 (Multi-AZ) |
| RDS PostgreSQL | carpayin-backend DB (Multi-AZ, 자동 failover) |
| ElastiCache Redis | 세션·상태 캐시 (carpayin-redis / pms-redis) |
| Cognito | QR OAuth 인증, AAOS 앱 IoT Core 연결 (Identity Pool) |
| SQS | 입차 확인 이벤트 큐 |
| Lambda | SQS 트리거 → IoT Core 메시지 발행 |
| IoT Core | AAOS 앱·Webots와 MQTT 통신 (실시간 입차 알림, 차단기 제어) |
| ECR | 빌드 이미지 저장소 |
| SSM | EC2 원격 배포 명령 실행 (pms, mock-pg) |

---

## 폴더 구조

```
services/
  carpayin-backend/   메인 백엔드 API
  pms/                Mock 주차장 API
  mock-pg/            Mock 결제대행사 API
  mock-card/          Mock 카드사 API
  android-app/        AAOS Android 앱

docs/
  api/                OpenAPI 스펙 (car-pay-in-openapi.yaml)
  DB schemas/         DB·Redis 스키마 문서
  diagrams/           Mermaid 시퀀스 다이어그램
  use-cases/          유스케이스 명세
  scenarios/          시나리오 흐름 문서
  deployment/         AWS 환경변수·배포 가이드
  conventions/        테스트 작성 컨벤션

infra/                Terraform (VPC, ECS, RDS 등)
infra_ec2/            EC2용 Docker Compose (pms, mock-pg)
scripts/              로컬 실행·빌드 스크립트
```

---

## 문서 맵

| 문서 | 경로 |
|------|------|
| API 명세 | `docs/api/car-pay-in-openapi.yaml` |
| DB 스키마 | `docs/DB schemas/` |
| 시퀀스 다이어그램 | `docs/diagrams/` |
| 유스케이스 명세 | `docs/use-cases/` |
| 시나리오 흐름 | `docs/scenarios/` |
| AWS 환경변수 | `docs/deployment/aws-env.md` |
| 테스트 컨벤션 | `docs/conventions/` |
| Android 설정 | `services/android-app/README.md` |

---

## 로컬 환경 설정

루트 `.env.example`을 복사해 로컬 시크릿을 채웁니다:

```powershell
Copy-Item .env.example .env
```

최소 필수 값:

```text
PUBLIC_BASE_URL         carpayin-backend 공개 URL (ngrok 등)
HYUNDAI_CLIENT_ID       현대차 OAuth 클라이언트 ID
HYUNDAI_CLIENT_SECRET   현대차 OAuth 클라이언트 시크릿
PG_PUBLIC_BASE_URL      mock-pg 공개 URL
```

Android 앱은 `services/android-app/local.properties`를 별도로 설정합니다.

---

## 로컬 실행

전체 서비스 스택 실행:

```powershell
docker compose up -d --build
```

로컬 포트:

```
8000  carpayin-backend
8001  pms
8002  mock-pg
8003  mock-card
5432  carpayin-postgres
5433  mock-card-postgres
5434  mock-pg-postgres
5435  pms-postgres
6379  carpayin-redis
6380  pms-redis
```

Swagger UI (스택 실행 후):

```
http://localhost:8000/docs   carpayin-backend
http://localhost:8001/docs   pms
http://localhost:8002/docs   mock-pg
http://localhost:8003/docs   mock-card
```

스크립트로 실행:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-local-full.ps1
```

---

## 테스트

전체 Python 서비스 단위·API 테스트 실행:

```powershell
make test
```

백엔드 테스트만 실행:

```powershell
cd services/carpayin-backend
python -m pytest tests/unit tests/api -q --import-mode=importlib
```

Android 컴파일:

```powershell
cd services/android-app
.\gradlew.bat :app:compileDebugKotlin
```

---

## CI/CD

모든 브랜치 push·MR에서 테스트를 실행하고, `main` 브랜치 push 시 서비스 코드 변경분만 빌드해 AWS에 자동 배포합니다.

```
test  → 모든 브랜치: Python 단위·API 테스트
build → main + 서비스 코드 변경: Docker 이미지 빌드 → AWS ECR 푸시
deploy
  carpayin-backend → ECS update-service (Fargate 롤링 배포)
  pms              → EC2 SSM send-command (docker compose up)
  mock-pg          → EC2 SSM send-command (docker compose up)
```

GitLab CI/CD 변수 필수 설정:

```text
AWS_REGION
ECS_CLUSTER
ECS_SERVICE_CARPAYIN_BACKEND
MOCKPMS_EC2_INSTANCE_ID
MOCKPG_EC2_INSTANCE_ID
MOCKPMS_DATABASE_URL    (Masked)
MOCKPMS_WEBHOOK_TOKEN   (Masked)
MOCKPG_DATABASE_URL     (Masked)
MOCKPG_WEBHOOK_SECRET   (Masked)
```

전체 AWS 환경변수 목록: `docs/deployment/aws-env.md`
