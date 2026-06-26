# mock-pg

Mock 결제대행사(PG) 서비스입니다.

## 담당 기능

- 카드 등록 WebView 제공
- 카드 등록 완료 처리 및 Mock 빌링키 발급
- 빌링키 기반 결제 요청 처리 (mock-card 연동)
- carpayin-backend로 웹훅 통보

## 폴더 구조

```
app/
  api/            FastAPI 라우터·스키마·의존성
  application/    PG 카드 등록·결제 유스케이스
  infra/          DB·Repository·외부 클라이언트
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

DB와 mock-card 의존성이 실행 중일 때 Docker 없이 서비스 실행:

```powershell
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8002
```

## 주요 엔드포인트

```
POST /pg/internal/card-registration/sessions
GET  /pg/card-register
POST /pg/card-register
POST /pg/payments/billing
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
| 원격 배포 | AWS SSM System Manager |

런타임 환경변수는 SSM send-command로 EC2에 `.env` 파일로 전달됩니다.
전체 환경변수 목록: `../../docs/deployment/aws-env.md`
