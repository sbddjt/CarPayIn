from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
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

# ── 현대 개발자 포털 설정 ───────────────────────────────────────────────────
HYUNDAI_CLIENT_ID     = "26b816d9-7764-42bd-bdbf-ff49f2e33098"
HYUNDAI_CLIENT_SECRET = "VcFPoKezkzlyhv0C4V3dMhIRyUz91OG70jdiAJLrCPd6rIPD"
HYUNDAI_REDIRECT_URI  = "http://localhost:8080/auth/redirect"

# 현대 계정 API (OpenID Connect)
HYUNDAI_TOKEN_URL    = "https://accounts.hyundai.com/auth/realms/HyundaiAccount/protocol/openid-connect/token"
HYUNDAI_USERINFO_URL = "https://accounts.hyundai.com/auth/realms/HyundaiAccount/protocol/openid-connect/userinfo"

# 현대 데이터 API (차량 정보 / VIN)
# TODO: developers.hyundai.com API 가이드에서 실제 엔드포인트 확인 후 교체
HYUNDAI_VEHICLE_LIST_URL = "https://api.hyundai.com/v1/spa/vehicles"

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
# 현대 OAuth 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.get("/auth/redirect", tags=["현대 OAuth"])
async def hyundai_auth_redirect(code: str = None, state: str = None, error: str = None):
    """
    현대 계정 API → 로그인 완료 후 authorization code 수신
    AAOS WebView가 이 URL을 intercept하므로 실제로 로드되는 경우는 드물지만
    브라우저 테스트 or fallback 용도로 서버에서도 처리
    """
    if error:
        print(f"[현대 OAuth] 로그인 오류: {error}")
        return HTMLResponse(f"<h3>로그인 실패: {error}</h3>", status_code=400)

    if not code:
        return HTMLResponse("<h3>Authorization Code 없음</h3>", status_code=400)

    print(f"[현대 OAuth] /auth/redirect — code 수신: {code[:8]}…")

    try:
        result = await _exchange_hyundai_code(code)
        # 앱 WebView가 intercept했을 경우 이 HTML은 표시 안 됨
        return HTMLResponse(f"""
            <html><body>
            <h3>✅ 현대 로그인 완료</h3>
            <p>user_id: {result.get('user_id','')}</p>
            <p>차량 수: {len(result.get('vin_list',[]))}대</p>
            <script>
              if(window.Android) {{
                window.Android.onHyundaiAuthComplete(
                  '{result.get("access_token","")}',
                  '{result.get("refresh_token","")}',
                  '{result.get("plate_number","")}',
                  '{result.get("user_id","")}'
                );
              }}
            </script>
            </body></html>
        """)
    except Exception as e:
        print(f"[현대 OAuth] 처리 실패: {e}")
        return HTMLResponse(f"<h3>처리 오류: {e}</h3>", status_code=500)


@app.post("/auth/hyundai/callback", tags=["현대 OAuth"])
async def hyundai_callback(request: Request):
    """
    AAOS 앱 → WebView에서 intercept한 authorization code를 백엔드로 전달
    백엔드가 현대 API 서버 투 서버 호출로 VIN + 차량 정보 조회
    앱과 서버 사이 인터넷 구간에는 code만 오가고 VIN은 노출되지 않음
    """
    body = await request.json()
    code     = body.get("code")
    vin_vhal = body.get("vin", "")       # VHAL에서 읽은 VIN (교차 검증용)
    cert_hash = body.get("cert_hash", "")

    if not code:
        raise HTTPException(400, "authorization code 필요")

    print(f"[현대 OAuth] /auth/hyundai/callback — code: {code[:8]}… VHAL VIN: {vin_vhal[:8]}…")

    result = await _exchange_hyundai_code(code, vin_vhal=vin_vhal, cert_hash=cert_hash)

    print(f"[현대 OAuth] 완료 — user_id: {result['user_id'][:6]}… VIN 수: {len(result['vin_list'])}개")
    return result


@app.get("/data/redirect", tags=["현대 OAuth"])
async def hyundai_data_redirect(code: str = None, error: str = None):
    """현대 데이터 API OAuth redirect 수신"""
    if error:
        return HTMLResponse(f"<h3>데이터 API 오류: {error}</h3>", status_code=400)
    print(f"[현대 데이터] /data/redirect — code: {(code or '')[:8]}…")
    return HTMLResponse("<h3>데이터 API 연동 완료</h3>")


@app.get("/data/callback", tags=["현대 OAuth"])
@app.post("/data/callback", tags=["현대 OAuth"])
async def hyundai_data_callback(request: Request):
    """현대 데이터 API 콜백 (차량 데이터 push 수신)"""
    body = {}
    try:
        body = await request.json()
    except Exception:
        pass
    print(f"[현대 데이터] /data/callback 수신: {body}")
    return {"status": "ok"}


# ── 현대 OAuth 내부 로직 ───────────────────────────────────────────────────

async def _exchange_hyundai_code(code: str, vin_vhal: str = "", cert_hash: str = "") -> dict:
    """
    Authorization Code → 현대 access_token 교환 → 차량 정보 조회 → CarPayIn 토큰 발급
    """
    async with httpx.AsyncClient() as client:

        # 1단계: authorization code → 현대 access_token
        token_res = await client.post(
            HYUNDAI_TOKEN_URL,
            data={
                "grant_type":   "authorization_code",
                "client_id":    HYUNDAI_CLIENT_ID,
                "client_secret": HYUNDAI_CLIENT_SECRET,
                "redirect_uri": HYUNDAI_REDIRECT_URI,
                "code":         code,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=10.0
        )
        if token_res.status_code != 200:
            raise HTTPException(502, f"현대 토큰 발급 실패: {token_res.text}")

        token_data         = token_res.json()
        hyundai_access     = token_data["access_token"]
        hyundai_refresh    = token_data.get("refresh_token", "")

        # 2단계: userinfo → user_id 조회
        userinfo_res = await client.get(
            HYUNDAI_USERINFO_URL,
            headers={"Authorization": f"Bearer {hyundai_access}"},
            timeout=10.0
        )
        user_info = userinfo_res.json() if userinfo_res.status_code == 200 else {}
        user_id   = user_info.get("sub", user_info.get("user_id", str(uuid.uuid4())))

        # 3단계: 차량 리스트 조회 → VIN + car_id
        # TODO: 실제 현대 차량 API 엔드포인트 확인 후 URL 수정
        vin_list = []
        try:
            vehicle_res = await client.get(
                HYUNDAI_VEHICLE_LIST_URL,
                headers={"Authorization": f"Bearer {hyundai_access}"},
                timeout=10.0
            )
            if vehicle_res.status_code == 200:
                vehicles = vehicle_res.json()
                for v in (vehicles if isinstance(vehicles, list) else vehicles.get("vehicles", [])):
                    vin_list.append({
                        "vin":        v.get("vin", ""),
                        "car_id":     v.get("carId", v.get("car_id", "")),
                        "model_name": v.get("modelName", v.get("model_name", "")),
                        "year":       v.get("year", 0),
                    })
        except Exception as e:
            print(f"[현대 API] 차량 리스트 조회 실패 (Mock fallback): {e}")
            # 차량 API 미승인 시 VHAL VIN으로 fallback
            if vin_vhal:
                vin_list = [{"vin": vin_vhal, "car_id": f"car_{vin_vhal[:8]}", "model_name": "Unknown", "year": 0}]

        # 4단계: VHAL VIN 교차 검증 (vin_vhal이 있으면 목록에 있는지 확인)
        matched_vin = vin_vhal
        if vin_vhal and vin_list:
            matched = next((v for v in vin_list if v["vin"] == vin_vhal), None)
            if not matched:
                print(f"[현대 OAuth] ⚠ VHAL VIN이 계정 차량 목록에 없음 — 첫 번째 차량으로 대체")
                matched_vin = vin_list[0]["vin"] if vin_list else vin_vhal
            else:
                matched_vin = matched["vin"]
        elif vin_list:
            matched_vin = vin_list[0]["vin"]

        car_info = next((v for v in vin_list if v["vin"] == matched_vin), {})

        # 5단계: 번호판 조회 (Mock 국토부 API)
        mock_plates = {
            "TESTVIN001": "123가4567",
            "TESTVIN002": "456나8901",
        }
        plate = mock_plates.get(matched_vin, f"000테{abs(hash(matched_vin)) % 9999:04d}")

        # 6단계: DB 저장 + CarPayIn 토큰 발급
        access_token  = f"at_{uuid.uuid4().hex}"
        refresh_token = f"rt_{uuid.uuid4().hex}"
        now = datetime.now().isoformat()

        # 마이현대에 등록된 결제 수단을 대표하는 billing_key (Mock)
        # 실제 서비스: 현대 페이 API에서 payment token 수신
        billing_key = f"hbk_{hashlib.sha256(user_id.encode()).hexdigest()[:24]}"

        with get_conn() as con:
            con.execute("""
                INSERT OR REPLACE INTO vehicles
                    (vin, plate, cert_hash, customer_key, payment_method_id,
                     registered_at, hyundai_user_id, hyundai_car_id, model_name, year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (matched_vin, plate, cert_hash, billing_key, f"pm_{billing_key[:12]}",
                  now, user_id, car_info.get("car_id",""), car_info.get("model_name",""), car_info.get("year",0)))

            con.execute("""
                INSERT OR REPLACE INTO hyundai_tokens
                    (vin, hyundai_access_token, hyundai_refresh_token, issued_at)
                VALUES (?, ?, ?, ?)
            """, (matched_vin, hyundai_access, hyundai_refresh, now))

            con.execute("""
                INSERT OR REPLACE INTO tokens (vin, access_token, refresh_token, issued_at)
                VALUES (?, ?, ?, ?)
            """, (matched_vin, access_token, refresh_token, now))

    return {
        "access_token":  access_token,
        "refresh_token": refresh_token,
        "plate_number":  plate,
        "user_id":       user_id,
        "vin_list":      vin_list,
    }


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
