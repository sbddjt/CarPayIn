from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from contextlib import asynccontextmanager
import uuid, time, hashlib, hmac as hmac_lib, json, base64, asyncio
from datetime import datetime
from urllib.parse import urlencode, quote
import httpx
from pydantic import BaseModel

from database import init_db, get_conn, token_expires_at, refresh_expires_at, cleanup_expired_records
from models import (
    ConfirmCarRequest,
    PreNotifyRequest, EntryWebhookRequest,
    PaymentRequest, PaidNotifyRequest
)
import mqtt_service

import os
PARKING_PMS_URL = os.environ.get("PARKING_PMS_URL", "http://localhost:8001")
MOCK_PG_URL     = os.environ.get("MOCK_PG_URL",     "http://localhost:9000")
HMAC_SECRET     = "mock_pg_secret_key_carpayin"
BACKEND_BUILD   = "hyundai-data-redirect-fix-20260513-1"

# ── 현대 개발자 포털 설정 ───────────────────────────────────────────────────
HYUNDAI_CLIENT_ID     = "26b816d9-7764-42bd-bdbf-ff49f2e33098"
HYUNDAI_CLIENT_SECRET = "VcFPoKezkzlyhv0C4V3dMhIRyUz91OG70jdiAJLrCPd6rIPD"

# ngrok URL — 환경변수로 주입하거나 아래 직접 교체
# export NGROK_URL=https://xxxx.ngrok-free.app  (서버 실행 전)
NGROK_URL            = os.environ.get("NGROK_URL", "https://pretext-armless-wieldable.ngrok-free.dev")
HYUNDAI_REDIRECT_URI = f"{NGROK_URL}/auth/redirect"

# 현대 계정 API
HYUNDAI_BASE_URL      = "https://prd.kr-ccapi.hyundai.com/api/v1"
HYUNDAI_DATA_BASE_URL = os.environ.get("HYUNDAI_DATA_BASE_URL", "https://dev.kr-ccapi.hyundai.com/api/v1")
HYUNDAI_AUTH_URL      = f"{HYUNDAI_BASE_URL}/user/oauth2/authorize"
HYUNDAI_TOKEN_URL     = f"{HYUNDAI_BASE_URL}/user/oauth2/token"
HYUNDAI_USERINFO_URL  = f"{HYUNDAI_BASE_URL}/user/profile"
HYUNDAI_SERVICE_AGREEMENT_URL = f"{HYUNDAI_DATA_BASE_URL}/car-service/terms/agreement"
HYUNDAI_SERVICE_REJECT_URL = f"{HYUNDAI_DATA_BASE_URL}/car-service/terms/reject"
HYUNDAI_VEHICLE_LIST_URL = f"{HYUNDAI_DATA_BASE_URL}/car/profile/carlist"


def _first_present(data: dict, *keys, default=""):
    for key in keys:
        value = data.get(key)
        if value not in (None, ""):
            return value
    return default


def _vehicle_items(payload):
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []

    candidates = [
        payload.get("vehicles"),
        payload.get("vehicleList"),
        payload.get("carList"),
        payload.get("cars"),
        payload.get("data"),
        payload.get("result"),
    ]
    for candidate in candidates:
        if isinstance(candidate, list):
            return candidate
        if isinstance(candidate, dict):
            nested = _vehicle_items(candidate)
            if nested:
                return nested
    return []


async def _cleanup_expired_records_loop():
    while True:
        await asyncio.sleep(300)
        try:
            cleanup_expired_records()
        except Exception as e:
            print(f"[DB 정리] 실패: {e}")


# ── 앱 시작 ────────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    mqtt_service.start()
    cleanup_task = asyncio.create_task(_cleanup_expired_records_loop())
    print("[백엔드] CarPayIn Backend Server 시작 (기본 포트 8002)")
    try:
        yield
    finally:
        cleanup_task.cancel()
        try:
            await cleanup_task
        except asyncio.CancelledError:
            pass

app = FastAPI(
    title="CarPayIn Backend",
    description="하이브리드 클라우드 인카 통합 페이먼트 백엔드",
    version="2.0.0",
    lifespan=lifespan
)

# ── 헬스 ──────────────────────────────────────────────────────────────────
@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "service": "CarPayIn Backend", "version": "2.0.0", "build": BACKEND_BUILD}


# ══════════════════════════════════════════════════════════════════════════
# 마이현대 OAuth 흐름
# ══════════════════════════════════════════════════════════════════════════

def _public_base_url(request: Request) -> str:
    proto = request.headers.get("x-forwarded-proto", request.url.scheme)
    host = request.headers.get("x-forwarded-host", request.headers.get("host", request.url.netloc))
    return f"{proto}://{host}".rstrip("/")


def _debug_message(value, limit: int = 700) -> str:
    if isinstance(value, HTTPException):
        value = value.detail
    text = str(value or "").strip()
    return text[:limit]


def _short(value, keep: int = 8) -> str:
    text = str(value or "")
    if not text:
        return "none"
    return text if len(text) <= keep else f"{text[:keep]}..."


def _mask_secret(value, head: int = 8, tail: int = 4) -> str:
    text = str(value or "")
    if not text:
        return "none"
    if len(text) <= head + tail:
        return f"{text[:head]}..."
    return f"{text[:head]}...{text[-tail:]}"


def _log_value(value, limit: int = 500) -> str:
    if isinstance(value, (dict, list, tuple)):
        text = json.dumps(value, ensure_ascii=False, default=str)
    else:
        text = str(value)
    return text if len(text) <= limit else f"{text[:limit]}..."


def _flow_log(flow: str, step: str, **fields):
    suffix = ""
    if fields:
        suffix = " | " + " ".join(f"{key}={_log_value(value)}" for key, value in fields.items())
    print(f"[{flow}] {step}{suffix}")


def _set_login_session_status(session_id: str, status: str, debug_message: str = ""):
    if not session_id:
        return
    with get_conn() as con:
        con.execute(
            "UPDATE login_sessions SET status=?, debug_message=? WHERE session_id=?",
            (status, _debug_message(debug_message), session_id)
        )
    _flow_log("로그인", "세션 상태 저장", session=_short(session_id), status=status, debug_message=_debug_message(debug_message))



def _resolve_oauth_state(state: str | None) -> str:
    """
    Mobile browsers sometimes replay an old Hyundai OAuth transaction after a new QR
    is scanned. If Hyundai returns a terminal old state while the app is polling a
    newer pending session, attach the callback to the newest pending session.
    """
    with get_conn() as con:
        current = None
        if state:
            current = con.execute(
                "SELECT session_id, status, created_at FROM login_sessions WHERE session_id=?",
                (state,)
            ).fetchone()
        latest_pending = con.execute("""
            SELECT session_id, created_at
            FROM login_sessions
            WHERE status='pending'
            ORDER BY created_at DESC
            LIMIT 1
        """).fetchone()

    if (
        latest_pending
        and latest_pending["session_id"] != state
        and (not current or (current["status"] or "") in ("complete", "error", "agreement_error"))
    ):
        _flow_log("로그인", "오래된 state를 최신 대기 세션으로 매핑", old_state=_short(state), new_state=_short(latest_pending["session_id"]))
        return latest_pending["session_id"]
    return state or ""

@app.get("/auth/hyundai/start", tags=["마이현대 OAuth"])
async def hyundai_start(request: Request, session_id: str, vin_hash: str = ""):
    """
    AAOS QR 스캔 → 스마트폰 브라우저가 이 URL을 엶
    session_id + vin_hash(SHA-256(vin+session_id))를 DB에 저장하고
    현대 OAuth 페이지로 리디렉션.
    QR URL에 평문 VIN을 노출하지 않기 위해 해시값만 전달.
    """
    now = datetime.now().isoformat()
    public_base_url = _public_base_url(request)
    _flow_log(
        "로그인",
        "QR 시작 요청 수신",
        session=_short(session_id),
        vin_hash=_short(vin_hash, 12),
        client_host=request.client.host if request.client else "unknown",
        public_base=public_base_url,
    )
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO login_sessions
                (session_id, vin_hash, status, created_at)
            VALUES (?, ?, 'pending', ?)
        """, (session_id, vin_hash, now))
    _flow_log("로그인", "대기 세션 저장", session=_short(session_id), status="pending", created_at=now)

    params = urlencode({
        "client_id":     HYUNDAI_CLIENT_ID,
        "redirect_uri":  f"{public_base_url}/auth/redirect",
        "response_type": "code",
        "scope":         "openid profile",
        "state":         session_id,
    })
    _flow_log("로그인", "현대 OAuth로 이동", session=_short(session_id), redirect_uri=f"{public_base_url}/auth/redirect")
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

    if not row:
        _flow_log("로그인상태", "조회 결과 없음", session=_short(session_id))
        return {"status": "pending"}
    if row["status"] != "complete":
        if (row["status"] or "pending") != "pending":
            _flow_log(
                "로그인상태",
                "처리중 상태 응답",
                session=_short(session_id),
                status=row["status"] or "pending",
                debug_message=row["debug_message"] if "debug_message" in row.keys() else "",
            )
        return {
            "status": row["status"] or "pending",
            "debug_message": row["debug_message"] if "debug_message" in row.keys() else "",
        }

    vin_list = []
    try:
        vin_list = json.loads(row["vin_list_json"] or "[]")
    except Exception:
        pass

    # 차량 테이블에서 카드 정보 조회 (OAuth 완료 시 저장됨)
    card_last_four = "0000"
    card_brand     = "현대카드"
    selected_car_id = row["selected_car_id"] if "selected_car_id" in row.keys() else ""
    if not selected_car_id and len(vin_list) == 1:
        selected_car_id = vin_list[0].get("car_id", "")
    if selected_car_id:
        with get_conn() as con:
            vrow = con.execute(
                "SELECT card_last_four, card_brand FROM vehicles WHERE car_id=?",
                (selected_car_id,)
            ).fetchone()
        if vrow:
            card_last_four = vrow["card_last_four"] or "0000"
            card_brand     = vrow["card_brand"]     or "현대카드"

    _flow_log(
        "로그인상태",
        "완료 상태 응답",
        session=_short(session_id),
        selected_car_id=_short(selected_car_id),
        vehicles=len(vin_list),
        card=f"{card_brand} ****{card_last_four}",
    )

    return {
        "status":         "complete",
        "access_token":   row["access_token"],
        "refresh_token":  row["refresh_token"],
        "plate_number":   row["plate_number"],
        "user_id":        row["user_id"],
        "user_name":      row["user_name"],
        "model_name":     row["model_name"],
        "vehicles":       vin_list,
        "vin_list":       vin_list,
        "selected_car_id": selected_car_id,
        "card_last_four": card_last_four,
        "card_brand":     card_brand,
    }


async def _exchange_hyundai_account_code(code: str, redirect_uri: str) -> dict:
    async with httpx.AsyncClient() as client:
        basic_token = base64.b64encode(
            f"{HYUNDAI_CLIENT_ID}:{HYUNDAI_CLIENT_SECRET}".encode()
        ).decode()
        token_res = await client.post(
            HYUNDAI_TOKEN_URL,
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
            },
            headers={
                "Authorization": f"Basic {basic_token}",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            timeout=10.0,
        )
        _flow_log(
            "현대 OAuth",
            "토큰 교환 응답",
            status=token_res.status_code,
            error_body=_debug_message(token_res.text, 300) if token_res.status_code != 200 else "",
        )
        if token_res.status_code != 200:
            raise HTTPException(502, f"Hyundai token failed ({token_res.status_code}): {token_res.text}")

        token_data = token_res.json()
        hyundai_access = token_data["access_token"]
        userinfo_res = await client.get(
            HYUNDAI_USERINFO_URL,
            headers={"Authorization": f"Bearer {hyundai_access}"},
            timeout=10.0,
        )
        user_info = userinfo_res.json() if userinfo_res.status_code == 200 else {}
        _flow_log(
            "현대 OAuth",
            "사용자 정보 응답",
            status=userinfo_res.status_code,
            user_id=_short(user_info.get("id", "")),
            user_name=user_info.get("name", ""),
        )
        return {
            "hyundai_access_token": hyundai_access,
            "hyundai_refresh_token": token_data.get("refresh_token", ""),
            "user_id": user_info.get("id", ""),
            "user_name": user_info.get("name", ""),
        }


async def _fetch_hyundai_vehicle_list(hyundai_access: str) -> list:
    async with httpx.AsyncClient() as client:
        vehicle_res = await client.get(
            HYUNDAI_VEHICLE_LIST_URL,
            headers={"Authorization": f"Bearer {hyundai_access}"},
            timeout=10.0,
        )
    print(f"[Hyundai Data API] carlist status={vehicle_res.status_code} body={vehicle_res.text[:1000]}")
    if vehicle_res.status_code != 200:
        raise HTTPException(502, f"Hyundai carlist failed ({vehicle_res.status_code}) at {HYUNDAI_VEHICLE_LIST_URL}: {vehicle_res.text}")

    vin_list = []
    for v in _vehicle_items(vehicle_res.json()):
        vin_list.append({
            "car_id": _first_present(v, "carId", "carID", "car_id", "carid", "id"),
            "model_name": _first_present(v, "carSellname", "carName", "modelName", "model_name", "carNickname"),
            "year": _first_present(v, "year", "modelYear", "model_year", default=0),
            "nickname": _first_present(v, "carNickname", "nickName", "nickname"),
            "car_type": _first_present(v, "carType", "type"),
        })
    parsed = [v for v in vin_list if v["car_id"]]
    _flow_log("현대 Data", "차량 목록 파싱", count=len(parsed), vehicles=parsed)
    return parsed


def _agreement_autopost_html(hyundai_access: str, state: str) -> str:
    token = f"Bearer {hyundai_access}"
    return f"""
        <html>
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body>
          <p>Hyundai data agreement is opening...</p>
          <form id="agreement" method="post" action="{HYUNDAI_SERVICE_AGREEMENT_URL}">
            <input type="hidden" name="token" value="{token}">
            <input type="hidden" name="state" value="{state}">
          </form>
          <script>document.getElementById('agreement').submit();</script>
        </body>
        </html>
    """


async def _complete_hyundai_data_agreement(state: str, user_id: str = "") -> dict:
    with get_conn() as con:
        row = con.execute(
            "SELECT * FROM login_sessions WHERE session_id=?", (state,)
        ).fetchone()
    if not row:
        raise HTTPException(404, "unknown agreement state")

    hyundai_access = row["access_token"]
    if not hyundai_access:
        raise HTTPException(400, "missing Hyundai access token")

    _flow_log(
        "현대 Data",
        "동의 완료 처리 시작",
        session=_short(state),
        callback_user_id=_short(user_id),
        saved_user_id=_short(row["user_id"]),
        access_token=_mask_secret(hyundai_access),
    )
    vin_list = await _fetch_hyundai_vehicle_list(hyundai_access)
    access_token = f"at_{uuid.uuid4().hex}"
    refresh_token = f"rt_{uuid.uuid4().hex}"
    now = datetime.now().isoformat()
    model_name = vin_list[0].get("model_name", "") if len(vin_list) == 1 else ""

    with get_conn() as con:
        con.execute("""
            UPDATE login_sessions SET
                status='complete',
                access_token=?, refresh_token=?,
                user_id=COALESCE(NULLIF(?, ''), user_id),
                model_name=?, vin_list_json=?
            WHERE session_id=?
        """, (
            access_token,
            refresh_token,
            user_id or row["user_id"],
            model_name,
            json.dumps(vin_list),
            state,
        ))
        con.execute("""
            INSERT OR REPLACE INTO tokens
                (access_token, refresh_token, car_id, hyundai_user_id, issued_at, expires_at, refresh_expires_at)
            VALUES (?, ?, '', ?, ?, ?, ?)
        """, (access_token, refresh_token, user_id or row["user_id"], now, token_expires_at(), refresh_expires_at()))

    _flow_log(
        "현대 Data",
        "로그인 완료 저장",
        session=_short(state),
        app_access_token=_mask_secret(access_token),
        hyundai_user_id=_short(user_id or row["user_id"]),
        vehicles=len(vin_list),
        model_name=model_name,
        issued_at=now,
    )
    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "vin_list": vin_list,
    }


@app.get("/data/redirect", tags=["마이현대 OAuth"])
@app.get("/auth/data-agreement/redirect", tags=["마이현대 OAuth"])
@app.get("/auth/redirect", tags=["마이현대 OAuth"])
async def hyundai_auth_redirect(
    request: Request,
    code: str = None,
    state: str = None,
    error: str = None,
    userId: str = None,
    useId: str = None,
    errCode: str = None,
    errMsg: str = None,
    errId: str = None,
):
    """
    현대 OAuth → 로그인 완료 후 authorization code + state(=session_id) 수신
    OAuth 교환 완료 후 login_sessions 테이블을 'complete' 로 업데이트
    → AAOS 앱 폴링이 이를 감지하고 자동으로 연동 완료 처리
    """
    raw_state = state or request.query_params.get("?state")
    state = _resolve_oauth_state(raw_state)
    agreement_user_id = userId or useId or request.query_params.get("userId") or request.query_params.get("useId")
    _flow_log(
        "현대 OAuth",
        "콜백 수신",
        path=request.url.path,
        raw_state=_short(raw_state),
        resolved_state=_short(state),
        code=_short(code),
        agreement_user_id=_short(agreement_user_id),
        err_code=errCode or "",
        error=error or "",
    )
    if agreement_user_id or errCode or request.url.path in ("/data/redirect", "/auth/data-agreement/redirect"):
        if errCode:
            _set_login_session_status(state or "", "agreement_error", f"{errCode}: {errMsg or ''}")
            _flow_log("현대 Data", "동의 콜백 오류", session=_short(state), err_code=errCode, err_msg=errMsg or "")
            return HTMLResponse(f"<h3>Hyundai data agreement failed</h3><p>{errCode}: {errMsg or ''}</p>", status_code=400)

        try:
            if not state:
                raise HTTPException(400, "missing Hyundai data agreement state")
            result = await _complete_hyundai_data_agreement(state, agreement_user_id or "")
            _flow_log("현대 Data", "동의 콜백 처리 완료", session=_short(state), vehicles=len(result["vin_list"]))
            return HTMLResponse("""
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="font-family:sans-serif;text-align:center;padding:60px 20px">
                  <h2>Hyundai data agreement complete</h2>
                  <p>You can return to the vehicle screen.</p>
                </body></html>
            """)
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            _set_login_session_status(state or "", "error", _debug_message(e))
            print(f"[Hyundai Data Agreement] failed: {e}\n{tb}")
            return HTMLResponse("""
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="font-family:sans-serif;text-align:center;padding:60px 20px">
                  <h2>Hyundai data agreement received</h2>
                  <p>You can return to the vehicle screen.</p>
                </body></html>
            """)

    if error:
        _flow_log("현대 OAuth", "로그인 오류 수신", session=_short(state), error=error)
        return HTMLResponse(f"<h3>로그인 실패: {error}</h3>", status_code=400)
    if not code:
        _flow_log("현대 OAuth", "Authorization code 누락", session=_short(state))
        return HTMLResponse("<h3>Authorization Code 없음</h3>", status_code=400)

    _flow_log("현대 OAuth", "Authorization code 수신", session=_short(state), code=_short(code))

    try:
        if state:
            _set_login_session_status(state, "authorized")

        vin_hash = ""
        if state:
            with get_conn() as con:
                row = con.execute(
                    "SELECT vin_hash FROM login_sessions WHERE session_id=?", (state,)
                ).fetchone()
                if row:
                    vin_hash = row["vin_hash"] or ""
        _flow_log("현대 OAuth", "세션 조회 완료", session=_short(state), vin_hash=_short(vin_hash, 12))

        redirect_uri = f"{_public_base_url(request)}/auth/redirect"
        _flow_log("현대 OAuth", "토큰 교환 시작", session=_short(state), redirect_uri=redirect_uri)
        account = await _exchange_hyundai_account_code(code, redirect_uri)
        access_token = account["hyundai_access_token"]
        refresh_token = account["hyundai_refresh_token"]
        user_id = account.get("user_id", "")
        user_name = account.get("user_name", "")
        issued_at = datetime.now().isoformat()
        hyundai_access_expires_at = token_expires_at()
        hyundai_refresh_expires_at = refresh_expires_at()
        if state:
            with get_conn() as con:
                con.execute("""
                    UPDATE login_sessions SET
                        status='agreement_required', debug_message='',
                        access_token=?, refresh_token=?,
                        user_id=?, user_name=?
                    WHERE session_id=?
                """, (access_token, refresh_token, user_id, user_name, state))
                con.execute("""
                    INSERT OR REPLACE INTO hyundai_tokens
                        (hyundai_user_id, car_id, hyundai_access_token, hyundai_refresh_token,
                         issued_at, access_expires_at, refresh_expires_at)
                    VALUES (?, '', ?, ?, ?, ?, ?)
                """, (
                    user_id,
                    access_token,
                    refresh_token,
                    issued_at,
                    hyundai_access_expires_at,
                    hyundai_refresh_expires_at,
                ))
        _flow_log(
            "현대 OAuth",
            "현대 토큰/사용자 저장",
            session=_short(state),
            hyundai_user_id=_short(user_id),
            user_name=user_name,
            access_token=_mask_secret(access_token),
            refresh_token=_mask_secret(refresh_token),
            access_expires_at=hyundai_access_expires_at,
            refresh_expires_at=hyundai_refresh_expires_at,
            next_step="data_agreement",
        )
        return HTMLResponse(_agreement_autopost_html(access_token, state or ""))
    except Exception as e:
        import traceback
        tb = traceback.format_exc()
        print(f"[현대 OAuth] 처리 실패: {e}")
        print(f"[현대 OAuth] 전체 traceback:\n{tb}")
        _set_login_session_status(state or "", "error", _debug_message(e))
        return HTMLResponse(
            f"<h3>처리 오류: {e}</h3><pre style='text-align:left;font-size:11px'>{tb}</pre>",
            status_code=500
        )


async def _read_callback_body(request: Request):
    raw = await request.body()
    content_type = request.headers.get("content-type", "")
    if "application/json" in content_type and raw:
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {"raw": raw.decode("utf-8", errors="replace")}
    return {"raw": raw.decode("utf-8", errors="replace")}


@app.get("/data/callback", tags=["마이현대 OAuth"])
@app.post("/data/callback", tags=["마이현대 OAuth"])
async def hyundai_data_callback(request: Request):
    body = await _read_callback_body(request)
    _flow_log("현대 Data", "콜백 수신", query=dict(request.query_params), body=body)
    return {"status": "ok"}


@app.post("/auth/confirm-car", tags=["마이현대 OAuth"])
def confirm_car(request: Request, req: ConfirmCarRequest):
    ctx = _token_context(request)
    access_token = ctx["access_token"]
    now = datetime.now().isoformat()
    _flow_log(
        "차량매핑",
        "확정 요청 수신",
        car_id=_short(req.car_id),
        vin_hash=_short(req.vin_hash, 12),
        token_car_id=_short(ctx["car_id"]),
        hyundai_user_id=_short(ctx["hyundai_user_id"]),
    )

    with get_conn() as con:
        session = con.execute(
            "SELECT * FROM login_sessions WHERE access_token=?",
            (access_token,)
        ).fetchone()
        if not session:
            _flow_log("차량매핑", "세션 조회 실패", access_token=_mask_secret(access_token))
            raise HTTPException(404, "login_session_not_found")

        expected_hash = session["vin_hash"] or ""
        _flow_log(
            "차량매핑",
            "로그인 세션 조회",
            session=_short(session["session_id"]),
            expected_vin_hash=_short(expected_hash, 12),
            vehicles_json_bytes=len(session["vin_list_json"] or ""),
        )
        if expected_hash and req.vin_hash != expected_hash:
            _flow_log(
                "차량매핑",
                "VIN hash 불일치",
                session=_short(session["session_id"]),
                expected=_short(expected_hash, 12),
                received=_short(req.vin_hash, 12),
            )
            raise HTTPException(409, "vin_hash_mismatch")

        try:
            vehicles = json.loads(session["vin_list_json"] or "[]")
        except Exception:
            vehicles = []
        selected = next((v for v in vehicles if v.get("car_id") == req.car_id), None)
        if not selected:
            _flow_log("차량매핑", "현대 차량 목록에서 car_id 없음", requested_car_id=_short(req.car_id), candidates=len(vehicles))
            raise HTTPException(404, "car_id_not_in_hyundai_list")

        model_name = selected.get("model_name", "") or session["model_name"] or ""
        year = int(selected.get("year") or 0)
        user_id = session["user_id"] or ctx["hyundai_user_id"]
        _flow_log(
            "차량매핑",
            "선택 차량 확인",
            car_id=_short(req.car_id),
            model_name=model_name,
            year=year,
            user_id=_short(user_id),
        )

        existing = con.execute(
            "SELECT car_id FROM vehicles WHERE car_id=?",
            (req.car_id,)
        ).fetchone()
        if existing:
            con.execute("""
                UPDATE vehicles SET
                    hyundai_user_id=?,
                    model_name=COALESCE(NULLIF(?, ''), model_name),
                    year=CASE WHEN ? > 0 THEN ? ELSE year END,
                    vin_hash=''
                WHERE car_id=?
            """, (user_id, model_name, year, year, req.car_id))
            vehicle_save_mode = "update"
        else:
            con.execute("""
                INSERT INTO vehicles
                    (car_id, plate, customer_key, payment_method_id,
                     card_last_four, card_brand,
                     registered_at, hyundai_user_id, model_name, year, vin_hash)
                VALUES (?, '', '', '', '0000', '', ?, ?, ?, ?, ?)
            """, (req.car_id, now, user_id, model_name, year, ""))
            vehicle_save_mode = "insert"

        con.execute(
            "UPDATE tokens SET car_id=?, hyundai_user_id=? WHERE access_token=?",
            (req.car_id, user_id, access_token)
        )
        con.execute(
            "UPDATE login_sessions SET selected_car_id=?, model_name=?, vin_hash='' WHERE session_id=?",
            (req.car_id, model_name, session["session_id"])
        )
        if user_id:
            con.execute(
                "UPDATE hyundai_tokens SET car_id=? WHERE hyundai_user_id=?",
                (req.car_id, user_id)
            )
        _flow_log(
            "차량매핑",
            "DB 저장 완료",
            vehicle=vehicle_save_mode,
            car_id=_short(req.car_id),
            model_name=model_name,
            year=year,
            token_mapped="yes",
            login_session=_short(session["session_id"]),
            hyundai_token_mapped="yes" if user_id else "no_user_id",
            vin_hash_cleared="yes",
        )

    _flow_log("차량매핑", "확정 응답", car_id=_short(req.car_id), vin_hash=_short(req.vin_hash, 12))
    return {"status": "ok", "car_id": req.car_id}


@app.post("/auth/unregister", tags=["마이현대 OAuth"])
def unregister_vehicle(request: Request):
    """
    Clear the local CarPayIn registration for the carId bound to this token.
    This removes Hyundai link data, app tokens, payment method/card data,
    pending card orders, and parking state derived from the registration.
    """
    car_id = _car_id_from_token(request)
    with get_conn() as con:
        con.execute("""
            UPDATE vehicles SET
                plate='',
                customer_key='',
                payment_method_id='',
                card_last_four='0000',
                card_brand='',
                hyundai_user_id='',
                hyundai_car_id='',
                model_name='',
                year=0
            WHERE car_id=?
        """, (car_id,))
        con.execute("DELETE FROM tokens WHERE car_id=?", (car_id,))
        con.execute("UPDATE hyundai_tokens SET car_id='' WHERE car_id=?", (car_id,))
        con.execute("DELETE FROM card_orders WHERE car_id=?", (car_id,))
        con.execute("DELETE FROM pre_notify WHERE car_id=?", (car_id,))
        con.execute("UPDATE sessions SET status='cancelled' WHERE car_id=? AND status='active'", (car_id,))

    print(f"[등록 해제] car_id={car_id}")
    return {"status": "ok", "car_id": car_id}


@app.post("/auth/refresh", tags=["마이현대 OAuth"])
def refresh_token(request_body: dict):
    """
    액세스 토큰 갱신.
    리프레시 토큰 만료 시 HTTP 401 (detail: "refresh_expired") — 앱이 재로그인 유도.
    갱신 성공 시 액세스 토큰(24h) + 리프레시 토큰(30d) 모두 새로 발급.
    """
    refresh = request_body.get("refresh_token", "")
    if not refresh:
        raise HTTPException(400, "refresh_token 필요")

    with get_conn() as con:
        row = con.execute(
            "SELECT car_id, refresh_expires_at FROM tokens WHERE refresh_token=?", (refresh,)
        ).fetchone()

    if not row:
        raise HTTPException(401, "유효하지 않은 refresh_token")

    # 리프레시 토큰 만료 체크
    r_expires = row["refresh_expires_at"] or ""
    if r_expires and datetime.now().isoformat() > r_expires:
        with get_conn() as con:
            con.execute("DELETE FROM tokens WHERE refresh_token=?", (refresh,))
        _flow_log("토큰", "만료된 refresh token 삭제", car_id=_short(row["car_id"]))
        raise HTTPException(401, "refresh_expired")

    new_access  = f"at_{uuid.uuid4().hex}"
    new_refresh = f"rt_{uuid.uuid4().hex}"
    now         = datetime.now().isoformat()
    new_exp     = token_expires_at()
    new_r_exp   = refresh_expires_at()

    with get_conn() as con:
        con.execute("""
            UPDATE tokens
            SET access_token=?, refresh_token=?, issued_at=?,
                expires_at=?, refresh_expires_at=?
            WHERE refresh_token=?
        """, (new_access, new_refresh, now, new_exp, new_r_exp, refresh))

    return {
        "access_token":  new_access,
        "refresh_token": new_refresh,
        "expires_at":    new_exp,
    }


# 차량 및 카드 등록 흐름
# ══════════════════════════════════════════════════════════════════════════

def _token_context(request: Request) -> dict:
    """
    Authorization 헤더의 Bearer 토큰 → 서버 내부에서 carId 조회.
    VIN을 URL/바디에 싣지 않고 carId로 결제/주차 흐름을 이어간다.
    액세스 토큰 만료 시 HTTP 401 (detail: "token_expired") 반환.
    """
    auth = request.headers.get("Authorization", "")
    token = auth.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(401, "Authorization 헤더 필요")
    with get_conn() as con:
        row = con.execute(
            "SELECT access_token, car_id, hyundai_user_id, expires_at FROM tokens WHERE access_token=?", (token,)
        ).fetchone()
    if not row:
        raise HTTPException(401, "유효하지 않은 토큰")
    # 만료 시각 체크
    expires = row["expires_at"] or ""
    if expires and datetime.now().isoformat() > expires:
        raise HTTPException(401, "token_expired")
    return {
        "access_token": row["access_token"],
        "car_id": row["car_id"] or "",
        "hyundai_user_id": row["hyundai_user_id"] or "",
    }


def _car_id_from_token(request: Request) -> str:
    car_id = _token_context(request)["car_id"]
    if not car_id:
        raise HTTPException(409, "confirmed_car_required")
    return car_id


class UpdatePlateRequest(BaseModel):
    plate: str

class CardOrderRequest(BaseModel):
    plate: str
    bank_name: str = ""
    agree_terms: bool = True  # 앱에서 약관 동의를 받았는지 확인

@app.post("/card/order", tags=["카드 등록"])
def create_card_order(request: Request, req: CardOrderRequest):
    """
    [신규 통합 흐름]
    앱에서 '동의 -> 번호판 입력 -> 은행 선택'을 마친 후 호출합니다.
    차량 번호를 DB에 검증/저장한 뒤에야 카드 결제창(Mock PG) URL을 내어줍니다.
    """
    # 1. 사전 검증 (동의 여부 및 번호판 누락 방지)
    if not req.agree_terms:
        raise HTTPException(status_code=400, detail="개인정보 제공에 동의해야 합니다.")
    if not req.plate.strip():
        raise HTTPException(status_code=400, detail="차량 번호를 입력해야 합니다.")

    car_id = _car_id_from_token(request)
    _flow_log(
        "카드매핑",
        "카드 주문 요청 수신",
        car_id=_short(car_id),
        plate=req.plate,
        bank_name=req.bank_name or "none",
        agree_terms=req.agree_terms,
    )

    with get_conn() as con:
        row = con.execute("SELECT car_id FROM vehicles WHERE car_id=?", (car_id,)).fetchone()
        if not row:
            _flow_log("카드매핑", "차량 조회 실패", car_id=_short(car_id))
            raise HTTPException(404, "등록되지 않은 car_id — OAuth 먼저 완료하세요")

        # 2. 번호판 업데이트 (결제창 띄우기 직전에 확정 저장)
        con.execute("UPDATE vehicles SET plate=? WHERE car_id=?", (req.plate, car_id))
        _flow_log("카드매핑", "차량 번호 저장", car_id=_short(car_id), plate=req.plate)

        # 3. 오더 ID 생성 및 DB 저장
        order_id = uuid.uuid4().hex[:20]
        now = datetime.now().isoformat()
        con.execute(
            "INSERT OR REPLACE INTO card_orders (order_id, car_id, created_at) VALUES (?, ?, ?)",
            (order_id, car_id, now)
        )
        _flow_log("카드매핑", "카드 주문 저장", order_id=_short(order_id), car_id=_short(car_id), created_at=now)

    # 4. PG URL 생성
    pg_url = f"{MOCK_PG_URL}/card-register?order_id={order_id}"

    if req.bank_name:
        encoded_bank = quote(req.bank_name)
        pg_url += f"&card_brand={encoded_bank}"

    _flow_log("카드매핑", "PG URL 발급", order_id=_short(order_id), car_id=_short(car_id), pg_url=pg_url)

    return {
        "status": "ready",
        "order_id": order_id,
        "pg_url": pg_url,
        "plate": req.plate
    }

@app.get("/card/order", tags=["카드 등록"])
def create_card_order_legacy(request: Request):
    """
    이전 앱 빌드 호환용.
    최신 앱은 POST /card/order로 번호판과 카드사를 넘기지만,
    서버를 재시작하지 않은 상태나 구버전 앱이 섞여도 PG URL 발급은 가능하게 둔다.
    """
    car_id = _car_id_from_token(request)
    _flow_log("카드매핑", "구버전 카드 주문 요청 수신", car_id=_short(car_id))
    with get_conn() as con:
        row = con.execute("SELECT car_id FROM vehicles WHERE car_id=?", (car_id,)).fetchone()
        if not row:
            _flow_log("카드매핑", "구버전 차량 조회 실패", car_id=_short(car_id))
            raise HTTPException(404, "등록되지 않은 car_id — OAuth 먼저 완료하세요")

        order_id = uuid.uuid4().hex[:20]
        now = datetime.now().isoformat()
        con.execute(
            "INSERT OR REPLACE INTO card_orders (order_id, car_id, created_at) VALUES (?, ?, ?)",
            (order_id, car_id, now)
        )
        _flow_log("카드매핑", "구버전 카드 주문 저장", order_id=_short(order_id), car_id=_short(car_id), created_at=now)

    pg_url = f"{MOCK_PG_URL}/card-register?order_id={order_id}"
    _flow_log("카드매핑", "구버전 PG URL 발급", order_id=_short(order_id), car_id=_short(car_id), pg_url=pg_url)
    return {"status": "ready", "order_id": order_id, "pg_url": pg_url}

@app.post("/webhook/card", tags=["카드 등록"])
async def card_webhook(request: Request):
    """
    Mock PG → 카드 등록 완료 웹훅
    HMAC 서명 검증 → order_id로 carId 조회 → customer_key + 카드 정보 저장
    """
    data = await request.json()
    order_id      = data.get("order_id", "")
    customer_key  = data.get("customer_key", "")
    card_brand    = data.get("card_brand", "")
    last_four     = data.get("last_four", "")
    received_hmac = data.get("hmac", "")
    _flow_log(
        "카드웹훅",
        "PG 웹훅 수신",
        order_id=_short(order_id),
        customer_key=_mask_secret(customer_key),
        card=f"{card_brand} ****{last_four}",
        hmac_present=bool(received_hmac),
    )

    # HMAC 검증
    hmac_payload = {
        "card_brand":   card_brand,
        "customer_key": customer_key,
        "last_four":    last_four,
        "order_id":     order_id,
    }
    payload_str = json.dumps(hmac_payload, sort_keys=True, separators=(',', ':'))
    expected = hmac_lib.new(HMAC_SECRET.encode(), payload_str.encode(), hashlib.sha256).hexdigest()
    if not hmac_lib.compare_digest(expected, received_hmac):
        _flow_log("카드웹훅", "HMAC 검증 실패", order_id=_short(order_id), expected=_short(expected, 12), received=_short(received_hmac, 12))
        raise HTTPException(403, "HMAC 검증 실패")
    _flow_log("카드웹훅", "HMAC 검증 성공", order_id=_short(order_id))

    # order_id -> car_id lookup
    with get_conn() as con:
        row = con.execute(
            "SELECT car_id FROM card_orders WHERE order_id=?", (order_id,)
        ).fetchone()
    if not row:
        _flow_log("카드웹훅", "주문 매핑 조회 실패", order_id=_short(order_id))
        raise HTTPException(404, f"유효하지 않은 order_id: {order_id[:8]}…")

    car_id            = row["car_id"]
    payment_method_id = f"pm_{customer_key[:12]}"
    _flow_log("카드웹훅", "주문 매핑 조회", order_id=_short(order_id), car_id=_short(car_id), payment_method_id=payment_method_id)

    with get_conn() as con:
        result = con.execute("""
            UPDATE vehicles SET
                customer_key=?, payment_method_id=?,
                card_last_four=?, card_brand=?
            WHERE car_id=?
        """, (customer_key, payment_method_id, last_four, card_brand, car_id))
        con.execute("DELETE FROM card_orders WHERE order_id=?", (order_id,))
        _flow_log(
            "카드웹훅",
            "카드 정보 저장",
            car_id=_short(car_id),
            customer_key=_mask_secret(customer_key),
            payment_method_id=payment_method_id,
            card=f"{card_brand} ****{last_four}",
            updated_rows=result.rowcount,
            order_deleted=_short(order_id),
        )

    _flow_log("카드웹훅", "처리 완료 응답", car_id=_short(car_id), payment_method_id=payment_method_id)
    return {"status": "ok", "car_id": car_id, "payment_method_id": payment_method_id}


# ══════════════════════════════════════════════════════════════════════════
# 주차장 목록
# ══════════════════════════════════════════════════════════════════════════

@app.get("/parking/lots", tags=["주차장"])
def get_parking_lots():
    """
    AAOS 앱 GeofenceManager용 제휴 주차장 목록
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
async def pre_notify(request: Request, req: PreNotifyRequest):
    """
    앱 지오펜스 진입 or 내비 목적지 설정
    → DB에 incoming 등록 + 아이파킹 PMS에 번호판 사전 등록
    """
    token_car_id = _car_id_from_token(request)
    if req.car_id != token_car_id:
        raise HTTPException(403, "car_id_token_mismatch")

    now = datetime.now().isoformat()
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO pre_notify (plate, lot_id, car_id, created_at)
            VALUES (?, ?, ?, ?)
        """, (req.plate, req.lot_id, req.car_id, now))

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
            "SELECT car_id FROM pre_notify WHERE plate=? AND lot_id=?",
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
        car_id     = pre["car_id"]

        con.execute("""
            INSERT INTO sessions (session_id, car_id, plate, lot_id, entry_time, status)
            VALUES (?, ?, ?, ?, ?, 'active')
        """, (session_id, car_id, req.plate, req.lot_id, entry_time))
        con.execute(
            "DELETE FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        )

    mqtt_service.notify_entry(session_id, req.lot_id, req.plate, entry_time, car_id=car_id)
    print(f"[입차확정] plate={req.plate} session={session_id[:8]}…")
    return {"status": "confirmed", "session_id": session_id, "entry_time": entry_time}


# ══════════════════════════════════════════════════════════════════════════
# 출차 / 결제 흐름
# ══════════════════════════════════════════════════════════════════════════

@app.get("/fee/{session_id}", tags=["결제"])
def get_fee(session_id: str, request: Request):
    """앱 시동 ON → 요금 조회"""
    with get_conn() as con:
        row = con.execute(
            "SELECT car_id, lot_id, entry_time FROM sessions WHERE session_id=? AND status='active'",
            (session_id,)
        ).fetchone()

    if not row:
        raise HTTPException(404, "활성 세션 없음")
    if row["car_id"] != _car_id_from_token(request):
        raise HTTPException(403, "car_id_token_mismatch")

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
async def process_payment(request: Request, req: PaymentRequest):
    """
    앱 '예' 탭 → 결제 처리
    세션 → 차량 고객키 조회 → Mock PG /charge → PMS paid 통보 → MQTT 알림
    """
    with get_conn() as con:
        session = con.execute(
            "SELECT car_id, plate, lot_id FROM sessions WHERE session_id=? AND status='active'",
            (req.session_id,)
        ).fetchone()
    if not session:
        raise HTTPException(404, "활성 세션 없음")

    car_id = session["car_id"]
    plate  = session["plate"]
    lot_id = session["lot_id"]
    if car_id != _car_id_from_token(request):
        raise HTTPException(403, "car_id_token_mismatch")

    with get_conn() as con:
        veh = con.execute(
            "SELECT customer_key FROM vehicles WHERE car_id=?", (car_id,)
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

    mqtt_service.notify_payment(tx_id, approval_no, lot_id, req.amount, car_id=car_id)
    print(f"[결제완료] plate={plate} amount={req.amount:,}원 tx={tx_id[:12]}… approval={approval_no}")

    return {
        "status":      "paid",
        "tx_id":       tx_id,
        "approval_no": approval_no,
        "amount":      req.amount,
        "session_id":  req.session_id,
    }
