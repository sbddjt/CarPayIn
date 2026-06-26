# mock-card

Mock 카드사 API입니다. mock-pg 서비스에서만 호출됩니다.

## 담당 기능

- 카드 등록 입력값 검증
- 카드 데이터 토큰화 (Mock 카드 토큰 발급)
- 빌링키 기반 결제 승인 처리

## 폴더 구조

```
app/
  api/            FastAPI 라우터·스키마·의존성
  application/    카드 검증·승인 유스케이스
  infra/          DB·Repository·카드 보안 헬퍼
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
uvicorn app.main:app --reload --host 0.0.0.0 --port 8003
```

## 주요 엔드포인트

```
POST /cards/verify
POST /cards/charge
```

API 명세 원본: `../../docs/api/car-pay-in-openapi.yaml`

## AWS 배포

| 컴포넌트 | 구성 |
|---------|------|
| 서버 | EC2 (Ubuntu) + Docker Compose |
| 데이터베이스 | PostgreSQL on EC2 |

mock-card는 내부 전용 서비스이므로 외부 ALB 없이 mock-pg와 VPC 내부 통신합니다.
