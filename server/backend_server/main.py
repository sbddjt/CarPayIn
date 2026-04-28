from fastapi import FastAPI, HTTPException
from contextlib import asynccontextmanager
import uuid, time, hashlib, hmac as hmac_lib
from datetime import datetime
import httpx

from database import init_db, get_conn
from models import (
    RegisterVehicleRequest, ConfirmPlateRequest, CardWebhookRequest,
    PreNotifyRequest, EntryWebhookRequest,
    PaymentRequest, PaidNotifyRequest
)
import mqtt_service

PARKING_PMS_URL = "http://localhost:8001"
MOCK_PG_URL     = "http://localhost:9000"
HMAC_SECRET     = "mock_pg_secret_key_carpayin"

# ── 앱 시작 ────────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    mqtt_service.start()
    print("[백엔드] CarPayIn Backend Server 시작 (포트 8000)")
    yield

app = FastAPI(
    title="CarPayIn Backend",
    description="하이브리드 클라우드 인카 통합 페이먼트 백엔드",
    version="1.0.0",
    lifespan=lifespan
)

# ── 헬스 ──────────────────────────────────────────────────────────────────
@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "service": "CarPayIn Backend"}

# ══════════════════════════════════════════════════════════════════════════
# 최초 등록 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.post("/auth/register", tags=["등록"])
def register_vehicle(req: RegisterVehicleRequest):
    """
    AAOS 앱 → VIN + 인증서 해시로 차량 등록
    → 액세스 토큰 / 리프레시 토큰 발급
    """
    access_token  = f"at_{uuid.uuid4().hex}"
    refresh_token = f"rt_{uuid.uuid4().hex}"
    now = datetime.now().isoformat()

    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO vehicles (vin, cert_hash, registered_at)
            VALUES (?, ?, ?)
        """, (req.vin, req.cert_hash, now))
        con.execute("""
            INSERT OR REPLACE INTO tokens (vin, access_token, refresh_token, issued_at)
            VALUES (?, ?, ?, ?)
        """, (req.vin, access_token, refresh_token, now))

    print(f"[등록] VIN={req.vin}")
    return {
        "access_token":  access_token,
        "refresh_token": refresh_token,
        "vin":           req.vin
    }


@app.get("/auth/plate/{vin}", tags=["등록"])
def lookup_plate(vin: str):
    """
    Mock 국토부 API — VIN으로 번호판 조회
    실제 환경에서는 본인인증 후 국토부 API 호출
    """
    mock_plates = {
        "TESTVIN001": "123가4567",
        "TESTVIN002": "456나8901",
        "TESTVIN003": "789다2345",
    }
    plate = mock_plates.get(vin, f"000테{abs(hash(vin)) % 9999:04d}")
    return {"vin": vin, "plate": plate}


@app.post("/auth/confirm-plate", tags=["등록"])
def confirm_plate(req: ConfirmPlateRequest):
    """앱에서 번호판 확인 탭 → VIN-번호판 매핑 저장"""
    with get_conn() as con:
        existing = con.execute(
            "SELECT vin FROM vehicles WHERE vin=?", (req.vin,)
        ).fetchone()
        if not existing:
            raise HTTPException(404, "등록되지 않은 차량")
        con.execute(
            "UPDATE vehicles SET plate=? WHERE vin=?",
            (req.plate, req.vin)
        )
    print(f"[번호판확인] VIN={req.vin} plate={req.plate}")
    return {"status": "ok", "vin": req.vin, "plate": req.plate}


@app.get("/card/order/{vin}", tags=["등록"])
def create_card_order(vin: str):
    """
    카드 등록 WebView 진입 전 order_id 생성
    Redis 역할: DB에 order_id → VIN 임시 저장
    """
    order_id = f"ord_{uuid.uuid4().hex[:16]}"
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO pre_notify (plate, lot_id, vin, created_at)
            VALUES (?, ?, ?, ?)
        """, (f"ORDER_{order_id}", "CARD_REG", vin, datetime.now().isoformat()))
    print(f"[카드주문] VIN={vin} order_id={order_id}")
    return {"order_id": order_id, "pg_url": f"http://localhost:9000/card-register?order_id={order_id}"}


@app.post("/webhook/card", tags=["등록"])
def card_webhook(req: CardWebhookRequest):
    """
    Mock PG → 카드 등록 완료 웹훅
    HMAC 검증 → customer_key 저장 → payment_method_id 발급
    """
    # HMAC 검증
    payload_str = f'{{"card_brand":"{req.card_brand}","customer_key":"{req.customer_key}","last_four":"{req.last_four}","order_id":"{req.order_id}"}}'
    expected = hmac_lib.new(
        HMAC_SECRET.encode(), payload_str.encode(), hashlib.sha256
    ).hexdigest()

    if req.hmac != expected:
        print(f"[카드웹훅] HMAC 불일치 — expected={expected[:16]}… got={req.hmac[:16]}…")
        raise HTTPException(status_code=401, detail="HMAC 검증 실패")
    print(f"[카드웹훅] HMAC 검증 성공 ✓")

    payment_method_id = f"pm_{uuid.uuid4().hex[:16]}"

    with get_conn() as con:
        con.execute("""
            UPDATE vehicles SET customer_key=?, payment_method_id=?
            WHERE vin=(
                SELECT vin FROM pre_notify WHERE plate=?
            )
        """, (req.customer_key, payment_method_id, f"ORDER_{req.order_id}"))
        con.execute(
            "DELETE FROM pre_notify WHERE plate=?",
            (f"ORDER_{req.order_id}",)
        )

    print(f"[카드등록완료] order_id={req.order_id} pm={payment_method_id}")
    return {"status": "ok", "payment_method_id": payment_method_id}


# ══════════════════════════════════════════════════════════════════════════
# 입차 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.post("/pre-notify", tags=["입차"])
async def pre_notify(req: PreNotifyRequest):
    """
    앱 지오펜스 진입 or 내비 목적지 설정
    → DB에 incoming 등록 + 아이파킹 PMS에 번호판 사전 등록
    """
    now = datetime.now().isoformat()
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO pre_notify (plate, lot_id, vin, created_at)
            VALUES (?, ?, ?, ?)
        """, (req.plate, req.lot_id, req.vin, now))

    # 아이파킹 PMS에 번호판 사전 등록
    try:
        async with httpx.AsyncClient() as client:
            await client.post(f"{PARKING_PMS_URL}/register-plate", json={
                "plate":  req.plate,
                "lot_id": req.lot_id
            }, timeout=3.0)
        print(f"[사전알림] plate={req.plate} lot={req.lot_id} → PMS 등록 완료")
    except Exception as e:
        print(f"[사전알림] PMS 연결 실패 (로컬 등록만 처리): {e}")

    return {"status": "ok", "plate": req.plate, "lot_id": req.lot_id}


@app.post("/webhook/entry", tags=["입차"])
def entry_webhook(req: EntryWebhookRequest):
    """
    아이파킹 PMS → 입차 이벤트 웹훅
    멱등성 처리 → 파킹 세션 생성 → MQTT 앱 알림
    (실제: Kafka publish → Consumer 처리)
    """
    with get_conn() as con:
        # 사전 등록 확인
        pre = con.execute(
            "SELECT vin FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        ).fetchone()

        if not pre:
            print(f"[입차웹훅] 미등록 차량: {req.plate}")
            return {"status": "unregistered"}

        # 멱등성: 이미 활성 세션 있으면 무시
        existing = con.execute(
            "SELECT session_id FROM sessions WHERE plate=? AND lot_id=? AND status='active'",
            (req.plate, req.lot_id)
        ).fetchone()
        if existing:
            return {"status": "duplicate", "session_id": existing["session_id"]}

        session_id = str(uuid.uuid4())
        entry_time = datetime.now().isoformat()
        vin        = pre["vin"]

        # [Kafka 역할] 세션 생성 (실제: Kafka Consumer가 처리)
        con.execute("""
            INSERT INTO sessions (session_id, vin, plate, lot_id, entry_time, status)
            VALUES (?, ?, ?, ?, ?, 'active')
        """, (session_id, vin, req.plate, req.lot_id, entry_time))

        con.execute(
            "DELETE FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        )

    # MQTT → 앱 입차 확정 알림 (VIN 기반 토픽)
    mqtt_service.notify_entry(session_id, req.lot_id, req.plate, entry_time, vin=vin)

    print(f"[입차확정] plate={req.plate} session={session_id[:8]}...")
    return {
        "status":     "confirmed",
        "session_id": session_id,
        "entry_time": entry_time
    }


# ══════════════════════════════════════════════════════════════════════════
# 출차 / 결제 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.get("/fee/{session_id}", tags=["결제"])
def get_fee(session_id: str):
    """앱 시동 ON → 요금 조회"""
    with get_conn() as con:
        row = con.execute(
            "SELECT lot_id, entry_time FROM sessions WHERE session_id=? AND status='active'",
            (session_id,)
        ).fetchone()

    if not row:
        raise HTTPException(404, "활성 세션 없음")

    entry   = datetime.fromisoformat(row["entry_time"])
    elapsed = (datetime.now() - entry).total_seconds()
    minutes = int(elapsed / 60)
    amount  = max(500, (minutes // 30 + 1) * 500)

    return {
        "session_id":       session_id,
        "lot_id":           row["lot_id"],
        "lot_name":         f"{row['lot_id']} 주차장",
        "amount":           amount,
        "duration_minutes": minutes
    }


@app.post("/payment", tags=["결제"])
async def process_payment(req: PaymentRequest):
    """
    앱 예 탭 → 결제 처리
    idempotency_key 중복 방지 → Mock PG 호출 → 거래 저장 → PMS paid 전달
    """
    with get_conn() as con:
        session = con.execute(
            "SELECT lot_id, plate, vin FROM sessions WHERE session_id=? AND status='active'",
            (req.session_id,)
        ).fetchone()

        if not session:
            raise HTTPException(404, "세션 없음 또는 이미 완료")

        lot_id = session["lot_id"]
        plate  = session["plate"]
        vin    = session["vin"]

        # customer_key 조회
        vehicle = con.execute(
            "SELECT customer_key FROM vehicles WHERE vin=?", (vin,)
        ).fetchone()
        customer_key = vehicle["customer_key"] if vehicle and vehicle["customer_key"] else "MOCK_CK"

        # idempotency_key 생성
        raw          = f"{req.session_id}:{req.amount}:{int(time.time() // 10)}"
        idem_key     = hashlib.sha256(raw.encode()).hexdigest()

        existing_tx = con.execute(
            "SELECT tx_id, approval_no FROM transactions WHERE idempotency_key=?",
            (idem_key,)
        ).fetchone()
        if existing_tx:
            return {"tx_id": existing_tx["tx_id"], "approval_no": existing_tx["approval_no"],
                    "amount": req.amount, "lot_id": lot_id}

    # Mock PG 호출
    try:
        async with httpx.AsyncClient() as client:
            pg_res = await client.post(f"{MOCK_PG_URL}/charge", json={
                "amount":          req.amount,
                "customer_key":    customer_key,
                "idempotency_key": idem_key
            }, timeout=5.0)
            pg_data     = pg_res.json()
            tx_id       = pg_data.get("tx_id",       str(uuid.uuid4()))
            approval_no = pg_data.get("approval_no", f"AP{int(time.time())}")
    except Exception as e:
        print(f"[결제] Mock PG 연결 실패, 자체 승인 처리: {e}")
        tx_id       = str(uuid.uuid4())
        approval_no = f"AP{int(time.time())}"

    now = datetime.now().isoformat()

    # [Kafka 역할] 거래 저장 + 세션 완료 (실제: Kafka Consumer)
    with get_conn() as con:
        con.execute("""
            INSERT INTO transactions
                (tx_id, session_id, lot_id, amount, approval_no, idempotency_key, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, req.session_id, lot_id, req.amount, approval_no, idem_key, now))
        con.execute(
            "UPDATE sessions SET status='completed', exit_time=?, amount=? WHERE session_id=?",
            (now, req.amount, req.session_id)
        )

    # 아이파킹 PMS에 paid 전달
    try:
        async with httpx.AsyncClient() as client:
            await client.post(f"{PARKING_PMS_URL}/paid", json={
                "plate":  plate,
                "lot_id": lot_id,
                "tx_id":  tx_id
            }, timeout=3.0)
        print(f"[결제] PMS paid 전달 완료: {plate}")
    except Exception as e:
        print(f"[결제] PMS paid 전달 실패 (재시도 필요): {e}")

    # MQTT → 앱 결제 완료 알림 (VIN 기반 토픽)
    mqtt_service.notify_payment(tx_id, approval_no, lot_id, req.amount, vin=vin)

    print(f"[결제완료] plate={plate} amount={req.amount:,}원 approval={approval_no}")
    return {
        "tx_id":       tx_id,
        "approval_no": approval_no,
        "amount":      req.amount,
        "lot_id":      lot_id
    }


# ══════════════════════════════════════════════════════════════════════════
# 디버그
# ══════════════════════════════════════════════════════════════════════════

@app.get("/sessions", tags=["디버그"])
def list_sessions():
    with get_conn() as con:
        rows = con.execute(
            "SELECT * FROM sessions ORDER BY entry_time DESC LIMIT 30"
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/vehicles", tags=["디버그"])
def list_vehicles():
    with get_conn() as con:
        rows = con.execute("SELECT * FROM vehicles").fetchall()
    return [dict(r) for r in rows]


@app.get("/transactions", tags=["디버그"])
def list_transactions():
    with get_conn() as con:
        rows = con.execute(
            "SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 30"
        ).fetchall()
    return [dict(r) for r in rows]


@app.delete("/debug/reset", tags=["디버그"])
def reset_all():
    with get_conn() as con:
        con.execute("DELETE FROM sessions")
        con.execute("DELETE FROM transactions")
        con.execute("DELETE FROM pre_notify")
    return {"status": "초기화 완료"}
