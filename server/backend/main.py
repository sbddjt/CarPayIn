from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from contextlib import asynccontextmanager
import uuid, time, hashlib, hmac as hmac_lib, json, base64
from datetime import datetime
from urllib.parse import urlencode
import httpx

from database import init_db, get_conn
from models import (
    ConfirmVinRequest,
    PreNotifyRequest, EntryWebhookRequest,
    PaymentRequest, PaidNotifyRequest
)
import mqtt_service

import os
PARKING_PMS_URL = os.environ.get("PARKING_PMS_URL", "http://localhost:8001")
MOCK_PG_URL     = os.environ.get("MOCK_PG_URL",     "http://localhost:9000")
HMAC_SECRET     = "mock_pg_secret_key_carpayin"

# ── 현대 개발자 포털 설정 ───────────────────────────────────────────────────
HYUNDAI_CLIENT_ID     = "26b816d9-7764-42bd-bdbf-ff49f2e33098"
HYUNDAI_CLIENT_SECRET = "VcFPoKezkzlyhv0C4V3dMhIRyUz91OG70jdiAJLrCPd6rIPD"

# ngrok URL — 환경변수로 주입하거나 아래 직접 교체
# export NGROK_URL=https://xxxx.ngrok-free.app  (서버 실행 전)
NGROK_URL            = os.environ.get("NGROK_URL", "https://pretext-armless-wieldable.ngrok-free.dev")
HYUNDAI_REDIRECT_URI = f"{NGROK_URL}/auth/redirect"

# 현대 계정 API
HYUNDAI_BASE_URL     = "https://prd.kr-ccapi.hyundai.com/api/v1"
HYUNDAI_AUTH_URL     = f"{HYUNDAI_BASE_URL}/user/oauth2/authorize"
HYUNDAI_TOKEN_URL    = f"{HYUNDAI_BASE_URL}/user/oauth2/token"
HYUNDAI_USERINFO_URL = f"{HYUNDAI_BASE_URL}/user/profile"
HYUNDAI_VEHICLE_LIST_URL = f"{HYUNDAI_BASE_URL}/spa/vehicles"



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
    version="2.0.0",
    lifespan=lifespan
)

# ── 헬스 ──────────────────────────────────────────────────────────────────
@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "service": "CarPayIn Backend", "version": "2.0.0"}


# ══════════════════════════════════════════════════════════════════════════
# 마이현대 OAuth 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.get("/auth/hyundai/start", tags=["마이현대 OAuth"])
async def hyundai_start(session_id: str, vin_hash: str = ""):
    """
    AAOS QR 스캔 → 스마트폰 브라우저가 이 URL을 엶
    session_id + vin_hash(SHA-256(vin+session_id))를 DB에 저장하고
    현대 OAuth 페이지로 리디렉션.
    QR URL에 평문 VIN을 노출하지 않기 위해 해시값만 전달.
    """
    now = datetime.now().isoformat()
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO login_sessions
                (session_id, vin_hash, vin, status, created_at)
            VALUES (?, ?, '', 'pending', ?)
        """, (session_id, vin_hash, now))

    params = urlencode({
        "client_id":     HYUNDAI_CLIENT_ID,
        "redirect_uri":  HYUNDAI_REDIRECT_URI,
        "response_type": "code",
        "scope":         "openid profile",
        "state":         session_id,
    })
    print(f"[QR 시작] session={session_id[:8]}… vin_hash={vin_hash[:12] if vin_hash else '없음'}…")
    return RedirectResponse(f"{HYUNDAI_AUTH_URL}?{params}")


@app.get("/auth/session/{session_id}/status", tags=["마이현대 OAuth"])
def session_status(session_id: str):
    """
    AAOS 앱 폴링 (2초 간격) — QR 로그인 완료 여부 확인
    완료 시 토큰·차량 정보·카드 정보 일괄 반환
    """
    with get_conn() as con:
        row = con.execute(
            "SELECT * FROM login_sessions WHERE session_id=?", (session_id,)
        ).fetchone()

    if not row or row["status"] != "complete":
        return {"status": "pending"}

    vin_list = []
    try:
        vin_list = json.loads(row["vin_list_json"] or "[]")
    except Exception:
        pass

    # 차량 테이블에서 카드 정보 조회 (OAuth 완료 시 저장됨)
    card_last_four = "0000"
    card_brand     = "현대카드"
    matched_vin = row["vin"] or (vin_list[0]["vin"] if vin_list else "")
    if matched_vin:
        with get_conn() as con:
            vrow = con.execute(
                "SELECT card_last_four, card_brand FROM vehicles WHERE vin=?",
                (matched_vin,)
            ).fetchone()
        if vrow:
            card_last_four = vrow["card_last_four"] or "0000"
            card_brand     = vrow["card_brand"]     or "현대카드"

    return {
        "status":         "complete",
        "access_token":   row["access_token"],
        "refresh_token":  row["refresh_token"],
        "plate_number":   row["plate_number"],
        "user_id":        row["user_id"],
        "user_name":      row["user_name"],
        "model_name":     row["model_name"],
        "vin_list":       vin_list,
        "card_last_four": card_last_four,
        "card_brand":     card_brand,
    }


@app.get("/auth/redirect", tags=["마이현대 OAuth"])
async def hyundai_auth_redirect(code: str = None, state: str = None, error: str = None):
    """
    현대 OAuth → 로그인 완료 후 authorization code + state(=session_id) 수신
    OAuth 교환 완료 후 login_sessions 테이블을 'complete' 로 업데이트
    → AAOS 앱 폴링이 이를 감지하고 자동으로 연동 완료 처리
    """
    if error:
        print(f"[현대 OAuth] 로그인 오류: {error}")
        return HTMLResponse(f"<h3>로그인 실패: {error}</h3>", status_code=400)
    if not code:
        return HTMLResponse("<h3>Authorization Code 없음</h3>", status_code=400)

    print(f"[현대 OAuth] /auth/redirect — code={code[:8]}… session={state[:8] if state else '없음'}…")

    try:
        vin_hash = ""
        if state:
            with get_conn() as con:
                row = con.execute(
                    "SELECT vin_hash FROM login_sessions WHERE session_id=?", (state,)
                ).fetchone()
                if row:
                    vin_hash = row["vin_hash"] or ""

        result = await _exchange_hyundai_code(code, vin_hash=vin_hash, session_id=state or "")

        if state:
            model_name = ""
            if result.get("vin_list") and result.get("matched_vin"):
                matched = next(
                    (v for v in result["vin_list"] if v["vin"] == result["matched_vin"]),
                    result["vin_list"][0]
                )
                model_name = matched.get("model_name", "")

            with get_conn() as con:
                con.execute("""
                    UPDATE login_sessions SET
                        status='complete',
                        vin=?,
                        access_token=?, refresh_token=?, plate_number=?,
                        user_id=?, user_name=?, model_name=?, vin_list_json=?
                    WHERE session_id=?
                """, (
                    result.get("matched_vin", ""),
                    result["access_token"],
                    result["refresh_token"],
                    result["plate_number"],
                    result.get("user_id", ""),
                    result.get("user_name", ""),
                    model_name,
                    json.dumps(result.get("vin_list", [])),
                    state,
                ))
            print(f"[QR 완료] session={state[:8]}… → AAOS 앱 폴링 해제됨")

        return HTMLResponse("""
            <html>
            <head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { font-family: -apple-system, sans-serif; text-align: center;
                     padding: 60px 20px; background: #f5f5f5; }
              .card { background: white; border-radius: 16px; padding: 40px;
                      max-width: 320px; margin: 0 auto; box-shadow: 0 4px 20px rgba(0,0,0,0.1); }
              h2 { color: #00AA55; margin-bottom: 12px; }
              p  { color: #555; line-height: 1.6; }
            </style>
            </head>
            <body>
              <div class="card">
                <h2>✅ 마이현대 로그인 완료</h2>
                <p>차량 화면으로 돌아가세요.<br>앱이 자동으로 연동됩니다.</p>
              </div>
            </body>
            </html>
        """)
    except Exception as e:
        import traceback
        tb = traceback.format_exc()
        print(f"[현대 OAuth] 처리 실패: {e}")
        print(f"[현대 OAuth] 전체 traceback:\n{tb}")
        return HTMLResponse(
            f"<h3>처리 오류: {e}</h3><pre style='text-align:left;font-size:11px'>{tb}</pre>",
            status_code=500
        )


@app.post("/auth/confirm-vin", tags=["마이현대 OAuth"])
def confirm_vin(req: ConfirmVinRequest):
    """
    AAOS 앱 → OAuth 완료 후 최종 선택된 VIN + car_id 확정 알림
    (차량이 여러 대 등록된 계정에서 매칭 VIN을 서버에 명시적으로 저장)
    """
    with get_conn() as con:
        existing = con.execute(
            "SELECT vin FROM vehicles WHERE vin=?", (req.vin,)
        ).fetchone()
        if not existing:
            raise HTTPException(404, "등록되지 않은 VIN — OAuth 흐름을 먼저 완료하세요")
        con.execute(
            "UPDATE vehicles SET hyundai_car_id=? WHERE vin=?",
            (req.car_id, req.vin)
        )
    print(f"[VIN 확정] vin={req.vin[:8]}… car_id={req.car_id}")
    return {"status": "ok", "vin": req.vin}


@app.post("/auth/refresh", tags=["마이현대 OAuth"])
def refresh_token(request_body: dict):
    """액세스 토큰 갱신"""
    refresh = request_body.get("refresh_token", "")
    if not refresh:
        raise HTTPException(400, "refresh_token 필요")

    with get_conn() as con:
        row = con.execute(
            "SELECT vin FROM tokens WHERE refresh_token=?", (refresh,)
        ).fetchone()

    if not row:
        raise HTTPException(401, "유효하지 않은 refresh_token")

    new_access  = f"at_{uuid.uuid4().hex}"
    new_refresh = f"rt_{uuid.uuid4().hex}"
    now = datetime.now().isoformat()

    with get_conn() as con:
        con.execute("""
            UPDATE tokens SET access_token=?, refresh_token=?, issued_at=?
            WHERE refresh_token=?
        """, (new_access, new_refresh, now, refresh))

    return {"access_token": new_access, "refresh_token": new_refresh}


# ── 내부: 현대 OAuth code 교환 ────────────────────────────────────────────

async def _exchange_hyundai_code(code: str, vin_hash: str = "", session_id: str = "") -> dict:
    """
    Authorization Code → 현대 access_token 교환 → 차량 정보 조회
    → VIN 해시 검증 → CarPayIn 토큰 발급 → DB 저장

    VIN 보안:
      QR URL에 평문 VIN 대신 SHA-256(VIN + session_id) = vin_hash 를 실어 전송.
      OAuth 완료 후 현대 차량 목록의 각 VIN에 대해 SHA-256(vin + session_id) 를 계산,
      저장된 vin_hash 와 일치하는 VIN을 찾아 사용. (복호화 없이 재해시 검증)
    """
    async with httpx.AsyncClient() as client:

        basic_token = base64.b64encode(
            f"{HYUNDAI_CLIENT_ID}:{HYUNDAI_CLIENT_SECRET}".encode()
        ).decode()

        # 1단계: authorization code → 현대 access_token
        token_res = await client.post(
            HYUNDAI_TOKEN_URL,
            data={
                "grant_type":   "authorization_code",
                "code":         code,
                "redirect_uri": HYUNDAI_REDIRECT_URI,
            },
            headers={
                "Authorization": f"Basic {basic_token}",
                "Content-Type":  "application/x-www-form-urlencoded",
            },
            timeout=10.0
        )
        if token_res.status_code != 200:
            raise HTTPException(502, f"현대 토큰 발급 실패 (status={token_res.status_code}): {token_res.text}")

        token_data      = token_res.json()
        hyundai_access  = token_data["access_token"]
        hyundai_refresh = token_data.get("refresh_token", "")

        # 2단계: 사용자 프로필 조회
        userinfo_res = await client.get(
            HYUNDAI_USERINFO_URL,
            headers={"Authorization": f"Bearer {hyundai_access}"},
            timeout=10.0
        )
        user_info = userinfo_res.json() if userinfo_res.status_code == 200 else {}
        user_id   = user_info.get("id", str(uuid.uuid4()))
        user_name = user_info.get("name", "")

        # 3단계: 차량 리스트 조회
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
            print(f"[현대 API] 차량 리스트 조회 실패: {e}")

        # 4단계: VIN 해시 검증 (SHA-256(vin + session_id) 재계산으로 매칭)
        matched_vin = ""
        if vin_hash and session_id and vin_list:
            for v in vin_list:
                candidate_hash = hashlib.sha256(
                    f"{v['vin']}{session_id}".encode()
                ).hexdigest()
                if candidate_hash == vin_hash:
                    matched_vin = v["vin"]
                    print(f"[VIN 검증] ✓ 해시 일치 → vin={v['vin'][:8]}…")
                    break
            if not matched_vin:
                print(f"[VIN 검증] ⚠ vin_hash와 일치하는 VIN 없음 — 첫 번째 차량으로 대체")
                matched_vin = vin_list[0]["vin"] if vin_list else ""
        elif vin_list:
            # vin_hash 없이 진입한 경우 (QR URL에 vin_hash 미포함 — 하위 호환)
            matched_vin = vin_list[0]["vin"]
            print(f"[VIN 검증] vin_hash 없음 — 첫 번째 차량 사용: {matched_vin[:8]}…")

        car_info = next((v for v in vin_list if v["vin"] == matched_vin), {})

        # 5단계: 번호판 조회 (Mock 국토부 API)
        # 실제 서비스: 본인인증 후 국토부 자동차등록정보 API 호출
        mock_plates = {
            "TESTVIN001": "123가4567",
            "TESTVIN002": "456나8901",
        }
        plate = mock_plates.get(matched_vin, f"000테{abs(hash(matched_vin)) % 9999:04d}")

        # 6단계: CarPayIn 토큰 발급
        # 카드 정보는 이 시점에서 등록하지 않음 — Mock PG WebView 카드 등록 완료 후 /webhook/card로 수신
        access_token  = f"at_{uuid.uuid4().hex}"
        refresh_token = f"rt_{uuid.uuid4().hex}"
        now = datetime.now().isoformat()

        with get_conn() as con:
            con.execute("""
                INSERT OR REPLACE INTO vehicles
                    (vin, plate, customer_key, payment_method_id,
                     card_last_four, card_brand,
                     registered_at, hyundai_user_id, hyundai_car_id, model_name, year)
                VALUES (?, ?, '', '',
                        '0000', '',
                        ?, ?, ?, ?, ?)
            """, (
                matched_vin, plate,
                now, user_id,
                car_info.get("car_id", ""),
                car_info.get("model_name", ""),
                car_info.get("year", 0)
            ))
            con.execute("""
                INSERT OR REPLACE INTO hyundai_tokens
                    (vin, hyundai_access_token, hyundai_refresh_token, issued_at)
                VALUES (?, ?, ?, ?)
            """, (matched_vin, hyundai_access, hyundai_refresh, now))
            con.execute("""
                INSERT OR REPLACE INTO tokens (vin, access_token, refresh_token, issued_at)
                VALUES (?, ?, ?, ?)
            """, (matched_vin, access_token, refresh_token, now))

    print(f"[OAuth 완료] vin={matched_vin[:8] if matched_vin else '없음'}… user={user_id[:8]}… plate={plate} — 카드 등록 대기 중")
    return {
        "access_token":  access_token,
        "refresh_token": refresh_token,
        "plate_number":  plate,
        "user_id":       user_id,
        "user_name":     user_name,
        "vin_list":      vin_list,
        "matched_vin":   matched_vin,  # VIN 해시 검증으로 확정된 VIN
    }


# ══════════════════════════════════════════════════════════════════════════
# 카드 등록 (Mock PG WebView 흐름)
# ══════════════════════════════════════════════════════════════════════════

def _vin_from_token(request: Request) -> str:
    """
    Authorization 헤더의 Bearer 토큰 → 서버 내부에서 VIN 조회.
    VIN을 URL/바디에 싣지 않아 외부 노출 차단.
    """
    auth = request.headers.get("Authorization", "")
    token = auth.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(401, "Authorization 헤더 필요")
    with get_conn() as con:
        row = con.execute(
            "SELECT vin FROM tokens WHERE access_token=?", (token,)
        ).fetchone()
    if not row:
        raise HTTPException(401, "유효하지 않은 토큰")
    return row["vin"]


@app.get("/card/order", tags=["카드 등록"])
def create_card_order(request: Request):
    """
    앱 → Mock PG WebView URL 요청.
    VIN은 Authorization 헤더 토큰으로 서버 내부에서만 조회 (URL 노출 없음).
    order_id 생성 → DB 저장 → Mock PG 카드 입력 URL 반환

    실제 서비스: 카드사별 PG사 라우팅 (현대카드→현대페이, 신한→KG이니시스 등)
    데모: 단일 Mock PG 서버로 통합 처리
    """
    vin = _vin_from_token(request)
    with get_conn() as con:
        row = con.execute("SELECT vin FROM vehicles WHERE vin=?", (vin,)).fetchone()
        if not row:
            raise HTTPException(404, "등록되지 않은 VIN — OAuth 먼저 완료하세요")

    order_id = uuid.uuid4().hex[:20]   # 20자 hex (충분히 고유)
    now = datetime.now().isoformat()
    with get_conn() as con:
        con.execute(
            "INSERT OR REPLACE INTO card_orders (order_id, vin, created_at) VALUES (?, ?, ?)",
            (order_id, vin, now)
        )

    pg_url = f"{MOCK_PG_URL}/card-register?order_id={order_id}"
    print(f"[카드주문] vin=****{vin[-4:]} order_id={order_id[:8]}… pg_url={pg_url}")
    return {"order_id": order_id, "pg_url": pg_url}


@app.post("/webhook/card", tags=["카드 등록"])
async def card_webhook(request: Request):
    """
    Mock PG → 카드 등록 완료 웹훅
    HMAC 서명 검증 → order_id로 VIN 조회 → customer_key + 카드 정보 저장

    실제 서비스: TLS 뮤추얼 인증 또는 IP 화이트리스트로 PG사 검증
    """
    data = await request.json()
    order_id      = data.get("order_id", "")
    customer_key  = data.get("customer_key", "")
    card_brand    = data.get("card_brand", "")
    last_four     = data.get("last_four", "")
    received_hmac = data.get("hmac", "")

    # HMAC 검증 (Mock PG와 동일한 4개 필드 + sort_keys 직렬화)
    hmac_payload = {
        "card_brand":   card_brand,
        "customer_key": customer_key,
        "last_four":    last_four,
        "order_id":     order_id,
    }
    payload_str = json.dumps(hmac_payload, sort_keys=True, separators=(',', ':'))
    expected = hmac_lib.new(HMAC_SECRET.encode(), payload_str.encode(), hashlib.sha256).hexdigest()
    if not hmac_lib.compare_digest(expected, received_hmac):
        print(f"[카드웹훅] HMAC 불일치 — order_id={order_id[:8]}…")
        raise HTTPException(403, "HMAC 검증 실패")

    # order_id → VIN 조회
    with get_conn() as con:
        row = con.execute(
            "SELECT vin FROM card_orders WHERE order_id=?", (order_id,)
        ).fetchone()
    if not row:
        raise HTTPException(404, f"유효하지 않은 order_id: {order_id[:8]}…")

    vin               = row["vin"]
    payment_method_id = f"pm_{customer_key[:12]}"

    with get_conn() as con:
        con.execute("""
            UPDATE vehicles SET
                customer_key=?, payment_method_id=?,
                card_last_four=?, card_brand=?
            WHERE vin=?
        """, (customer_key, payment_method_id, last_four, card_brand, vin))
        con.execute("DELETE FROM card_orders WHERE order_id=?", (order_id,))

    print(f"[카드등록 완료] vin={vin[:8]}… card=****{last_four} ({card_brand})")
    return {"status": "ok", "vin": vin, "payment_method_id": payment_method_id}


# ══════════════════════════════════════════════════════════════════════════
# 주차장 목록
# ══════════════════════════════════════════════════════════════════════════

@app.get("/parking/lots", tags=["주차장"])
def get_parking_lots():
    """
    AAOS 앱 GeofenceManager용 제휴 주차장 목록
    실제 서비스: 아이파킹 등 PMS API에서 실시간 조회
    """
    lots = [
        {"id": "LOT_GN_01", "name": "강남 CarPayIn 주차장",  "lat": 37.4979, "lng": 127.0276},
        {"id": "LOT_HD_01", "name": "홍대 CarPayIn 주차장",  "lat": 37.5567, "lng": 126.9236},
        {"id": "LOT_SC_01", "name": "서초 CarPayIn 주차장",  "lat": 37.4836, "lng": 127.0323},
        {"id": "LOT_YS_01", "name": "여의도 CarPayIn 주차장", "lat": 37.5219, "lng": 126.9245},
    ]
    return {"lots": lots}


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

    try:
        async with httpx.AsyncClient() as client:
            await client.post(f"{PARKING_PMS_URL}/register-plate", json={
                "plate":  req.plate,
                "lot_id": req.lot_id
            }, timeout=3.0)
        print(f"[사전알림] plate={req.plate} lot={req.lot_id} trigger={req.trigger} → PMS 등록 완료")
    except Exception as e:
        print(f"[사전알림] PMS 연결 실패 (로컬 등록만 처리): {e}")

    return {"status": "ok", "plate": req.plate, "lot_id": req.lot_id}


@app.post("/webhook/entry", tags=["입차"])
def entry_webhook(req: EntryWebhookRequest):
    """
    아이파킹 PMS → 입차 이벤트 웹훅
    멱등성 처리 → 파킹 세션 생성 → MQTT 앱 알림
    """
    with get_conn() as con:
        pre = con.execute(
            "SELECT vin FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        ).fetchone()

        if not pre:
            print(f"[입차웹훅] 미등록 차량: {req.plate}")
            return {"status": "unregistered"}

        existing = con.execute(
            "SELECT session_id FROM sessions WHERE plate=? AND lot_id=? AND status='active'",
            (req.plate, req.lot_id)
        ).fetchone()
        if existing:
            return {"status": "duplicate", "session_id": existing["session_id"]}

        session_id = str(uuid.uuid4())
        entry_time = datetime.now().isoformat()
        vin        = pre["vin"]

        con.execute("""
            INSERT INTO sessions (session_id, vin, plate, lot_id, entry_time, status)
            VALUES (?, ?, ?, ?, ?, 'active')
        """, (session_id, vin, req.plate, req.lot_id, entry_time))
        con.execute(
            "DELETE FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        )

    mqtt_service.notify_entry(session_id, req.lot_id, req.plate, entry_time, vin=vin)
    print(f"[입차확정] plate={req.plate} session={session_id[:8]}…")
    return {"status": "confirmed", "session_id": session_id, "entry_time": entry_time}


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
        "duration_minutes": minutes,
    }


@app.post("/payment", tags=["결제"])
async def process_payment(req: PaymentRequest):
    """
    앱 '예' 탭 → 결제 처리
    세션 → 차량 고객키 조회 → Mock PG /charge → PMS paid 통보 → MQTT 알림
    """
    with get_conn() as con:
        session = con.execute(
            "SELECT vin, plate, lot_id FROM sessions WHERE session_id=? AND status='active'",
            (req.session_id,)
        ).fetchone()
    if not session:
        raise HTTPException(404, "활성 세션 없음")

    vin    = session["vin"]
    plate  = session["plate"]
    lot_id = session["lot_id"]

    with get_conn() as con:
        veh = con.execute(
            "SELECT customer_key FROM vehicles WHERE vin=?", (vin,)
        ).fetchone()
    if not veh or not veh["customer_key"]:
        raise HTTPException(400, "카드 미등록 — 카드 등록 후 이용하세요")

    try:
        async with httpx.AsyncClient() as client:
            charge_res = await client.post(f"{MOCK_PG_URL}/charge", json={
                "amount":          req.amount,
                "customer_key":    veh["customer_key"],
                "idempotency_key": f"{req.session_id}_{req.amount}",
            }, timeout=10.0)
        charge_data = charge_res.json()
        if not charge_data.get("success"):
            raise HTTPException(502, "결제 승인 실패")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"PG 통신 오류: {e}")

    tx_id       = charge_data["tx_id"]
    approval_no = charge_data["approval_no"]

    with get_conn() as con:
        con.execute(
            "UPDATE sessions SET status='paid' WHERE session_id=?",
            (req.session_id,)
        )

    # PMS paid 통보 → 출구 차단기 개방 허가
    try:
        async with httpx.AsyncClient() as client:
            await client.post(f"{PARKING_PMS_URL}/paid", json={
                "plate":  plate,
                "lot_id": lot_id,
                "tx_id":  tx_id,
            }, timeout=3.0)
    except Exception as e:
        print(f"[결제완료] PMS paid 통보 실패 (무시): {e}")

    mqtt_service.notify_payment(tx_id, approval_no, lot_id, req.amount, vin=vin)
    print(f"[결제완료] plate={plate} amount={req.amount:,}원 tx={tx_id[:12]}… approval={approval_no}")

    return {
        "status":      "paid",
        "tx_id":       tx_id,
        "approval_no": approval_no,
        "amount":      req.amount,
        "session_id":  req.session_id,
    }
