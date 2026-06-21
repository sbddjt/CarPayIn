"""
app/api/routes/load_test.py

부하테스트용 시드 데이터를 DB에 직접 심는 라우터.
부하테스트가 끝나면 main.py에서 등록한 두 줄을 지우면 된다.
"""

import uuid

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.infra.db.session import get_db_session
from app.infra.security import create_default_security_components

router = APIRouter(prefix="/dev", tags=["Dev"])

_security = create_default_security_components()
_app_token_issuer = _security["app_token_issuer"]
_refresh_token_hasher = _security["refresh_token_hasher"]

# 실제 번호판 형식: 숫자3자리 + 한글1자 + 숫자4자리 (예: 100가1000)
_PLATE_CHARS = ["가", "나", "다", "라", "마", "바", "사", "아", "자", "차"]


def _generate_plate(index: int) -> str:
    region_num = 100 + (index % 900)
    char = _PLATE_CHARS[index % len(_PLATE_CHARS)]
    seq = 1000 + index
    return f"{region_num}{char}{seq}"


# ── 스키마 ──────────────────────────────────────────────────────────────────

class SeedRequest(BaseModel):
    count: int = 1       # 생성할 유저 수 (최대 100)
    lot_id: str = "lot-357"


class SeededUser(BaseModel):
    user_id: str
    car_id: str
    plate: str
    lot_id: str
    access_token: str    # ← 부하테스트 스크립트에서 Bearer 로 그대로 사용
    billing_key: str     # ← MockPG 시드에 사용


class SeedResponse(BaseModel):
    count: int
    users: list[SeededUser]


class ParkingSessionResponse(BaseModel):
    session_id: str
    lot_id: str


# ── endpoint ─────────────────────────────────────────────────────────────────

@router.post("/seed-test-user", response_model=SeedResponse)
def seed_test_user(
    req: SeedRequest,
    session: Session = Depends(get_db_session),
) -> SeedResponse:
    count = min(max(req.count, 1), 500)
    seeded: list[SeededUser] = []

    for i in range(count):
        user_id = str(uuid.uuid4())
        car_id  = str(uuid.uuid4())
        plate   = _generate_plate(i)  # 예: 100가1000, 101나1001

        # 1. users
        session.execute(
            text("""
                INSERT INTO users (user_id, name)
                VALUES (:user_id, :name)
                ON CONFLICT (user_id) DO NOTHING
            """),
            {"user_id": user_id, "name": f"부하테스트유저{i}"},
        )

        # 2. vehicles
        session.execute(
            text("""
                INSERT INTO vehicles (car_id, user_id, car_sellname, plate)
                VALUES (:car_id, :user_id, :car_sellname, :plate)
                ON CONFLICT (car_id) DO UPDATE
                    SET plate = EXCLUDED.plate
            """),
            {
                "car_id":       car_id,
                "user_id":      user_id,
                "car_sellname": "부하테스트차량",
                "plate":        plate,
            },
        )

        # 3. vehicle_billing_keys
        billing_key = f"bk_load_{uuid.uuid4().hex[:16]}"
        session.execute(
            text("""
                INSERT INTO vehicle_billing_keys (car_id, billing_key, card_last_four, status)
                VALUES (:car_id, :billing_key, '0000', 'active')
                ON CONFLICT (car_id) DO UPDATE
                    SET billing_key = EXCLUDED.billing_key,
                        status      = 'active',
                        updated_at  = now()
            """),
            {"car_id": car_id, "billing_key": billing_key},
        )

        # 4. 토큰 발급 (AppTokenIssuer 사용 → 기존 validator 그대로 통과)
        tokens        = _app_token_issuer.issue(user_id=user_id, car_id=car_id)
        access_token  = tokens["access_token"]
        refresh_token = tokens["refresh_token"]
        rt_hash       = _refresh_token_hasher.hash(refresh_token)

        # 5. app_refresh_tokens (hash만 저장)
        session.execute(
            text("""
                INSERT INTO app_refresh_tokens (
                    refresh_token_hash, user_id, car_id,
                    status, expires_at
                ) VALUES (
                    :hash, :user_id, :car_id,
                    'active', now() + interval '30 days'
                )
                ON CONFLICT (refresh_token_hash) DO NOTHING
            """),
            {"hash": rt_hash, "user_id": user_id, "car_id": car_id},
        )

        seeded.append(SeededUser(
            user_id=user_id,
            car_id=car_id,
            plate=plate,
            lot_id=req.lot_id,
            access_token=access_token,
            billing_key=billing_key,
        ))

    session.commit()
    return SeedResponse(count=len(seeded), users=seeded)


@router.delete("/seed-test-user/{car_id}")
def delete_seed_user(
    car_id: str,
    session: Session = Depends(get_db_session),
) -> dict:
    row = session.execute(
        text("SELECT user_id FROM vehicles WHERE car_id = :car_id"),
        {"car_id": car_id},
    ).fetchone()

    if not row:
        raise HTTPException(status_code=404, detail="car_id not found")

    user_id = row.user_id

    for table, col, val in [
        ("app_refresh_tokens",   "car_id",  car_id),
        ("transactions",         "car_id",  car_id),
        ("parking_sessions",     "car_id",  car_id),
        ("vehicle_billing_keys", "car_id",  car_id),
        ("vehicles",             "car_id",  car_id),
        ("users",                "user_id", user_id),
    ]:
        session.execute(
            text(f"DELETE FROM {table} WHERE {col} = :v"),
            {"v": val},
        )

    session.commit()
    return {"status": "deleted", "car_id": car_id}


@router.get("/parking-session", response_model=ParkingSessionResponse)
def get_parking_session(
    car_id: str,
    session: Session = Depends(get_db_session),
) -> ParkingSessionResponse:
    row = session.execute(
        text("""
            SELECT session_id, lot_id FROM parking_sessions
            WHERE car_id = :car_id AND status = 'active'
            LIMIT 1
        """),
        {"car_id": car_id},
    ).fetchone()

    if not row:
        raise HTTPException(status_code=404, detail="active session not found")

    return ParkingSessionResponse(session_id=str(row.session_id), lot_id=row.lot_id or "")

@router.post("/seed-card-token")
def seed_card_token(
    card_token: str,
) -> dict:
    import httpx
    import os
    pg_base_url = os.getenv("PG_BASE_URL", "").rstrip("/")
    resp = httpx.post(
        f"{pg_base_url}/dev/seed-card-token",
        params={"card_token": card_token},
    )
    if resp.status_code != 200:
        return {"status": "failed", "detail": resp.text}
    return {"status": "ok", "card_token": card_token}