# mock-pg

Mock payment gateway service.

## Responsibilities

- Serve the local card registration WebView
- Complete card registration and issue mock billing keys
- Charge a billing key through `mock-card`
- Notify `carpayin-backend` through configured webhook URLs

## Structure

```text
app/
  api/            FastAPI routes, schemas, and dependencies
  application/    PG card registration and payment use cases
  infra/          Database, repositories, and external clients
migrations/       Alembic migrations
tests/            Unit, API, and integration tests
```

## Runtime

The container starts with:

```text
alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Local Development

Install dependencies and run tests from this service directory:

```powershell
pip install -r requirements.txt
python -m pytest tests/unit tests/api -q --import-mode=importlib
```

Run the service outside Docker when its database and mock-card dependency are
available:

```powershell
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8002
```

## Main Endpoints

- `POST /pg/internal/card-registration/sessions`
- `GET /pg/card-register`
- `POST /pg/card-register`
- `POST /pg/payments/billing`

The API contract is included in `../../docs/api/car-pay-in-openapi.yaml`.

## AWS Deployment

mock-pg는 AWS EC2에서 Docker Compose로 실행됩니다.

```
GitLab CI (main branch)
  └─ build: Docker 이미지 빌드 → AWS ECR 푸시
  └─ deploy: AWS SSM send-command → EC2에서 docker compose up
```

AWS 인프라 구성:

| 컴포넌트 | 구성 |
|---------|------|
| 서버 | EC2 (Ubuntu) + Docker Compose |
| 데이터베이스 | PostgreSQL on EC2 |
| 원격 배포 | AWS SSM System Manager |

런타임 환경변수는 SSM send-command로 EC2에 `.env` 파일로 전달됩니다.
전체 환경변수 목록: `../../docs/deployment/aws-env.md`
