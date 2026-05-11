"""
Mock PG (Payment Gateway) Server
FastAPI 기반 카드 등록 시뮬레이터

실행: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
AAOS 에뮬레이터 접근 URL: http://10.0.2.2:8000/card-register?order_id=TEST
"""

from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import uuid, os
import hmac
import hashlib
import json
import httpx

app = FastAPI(title="Mock PG Server", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

HMAC_SECRET   = "mock_pg_secret_key_carpayin"
BACKEND_URL   = os.environ.get("BACKEND_URL", "http://localhost:8000")

# ── 카드 등록 HTML 페이지 ───────────────────────────────────────────────────────

CARD_REGISTER_HTML = """<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>카드 등록</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
    background:#080C10; color:#fff;
    font-family:-apple-system,sans-serif;
    min-height:100vh; display:flex;
    flex-direction:column; align-items:center;
    justify-content:center; padding:20px;
}
h2 { font-size:18px; font-weight:bold; margin-bottom:4px; }
.sub { font-size:11px; color:#3A5070; margin-bottom:20px; }

/* 카드 프리뷰 */
.card {
    width:300px; height:175px; border-radius:14px;
    padding:20px; margin-bottom:20px; position:relative;
    background:#1A1A2E; transition:background 0.3s;
    box-shadow:0 8px 32px rgba(0,0,0,0.5);
}
.card-brand { font-size:11px; font-weight:bold; letter-spacing:2px; color:#CCC; }
.card-network {
    position:absolute; top:20px; right:20px;
    font-size:14px; font-weight:bold; color:#FFD700;
}
.card-chip {
    width:36px; height:26px; background:#C8A030;
    border-radius:4px; margin-top:10px;
}
.card-number {
    font-family:monospace; font-size:16px;
    letter-spacing:3px; margin-top:10px; color:#fff;
}
.card-valid-label { font-size:7px; color:#888; letter-spacing:1px; margin-top:10px; }
.card-expiry { font-family:monospace; font-size:12px; color:#CCC; }

/* 카드사 선택 */
.brands {
    display:flex; gap:8px; overflow-x:auto;
    width:100%; max-width:320px;
    padding-bottom:4px; margin-bottom:18px;
    scrollbar-width:none;
}
.brands::-webkit-scrollbar { display:none; }
.chip {
    flex-shrink:0; padding:7px 14px; border-radius:20px;
    background:#111820; color:#556677;
    font-size:12px; cursor:pointer;
    border:1.5px solid #1A2535;
    transition:all 0.2s; white-space:nowrap;
}
.chip.on { color:#fff; border-color:transparent; }

/* 입력 폼 */
.form-group { width:100%; max-width:320px; margin-bottom:12px; }
label { display:block; font-size:11px; color:#888; margin-bottom:6px; letter-spacing:0.5px; }
input {
    width:100%; height:48px; background:#111820;
    border:1.5px solid #1A2535; border-radius:8px;
    color:#fff; font-size:15px; font-family:monospace;
    padding:0 14px; outline:none;
}
input::placeholder { color:#2A3A4A; }
input:focus { border-color:#00E87A; }
.row { display:flex; gap:10px; }
.row .form-group { flex:1; }

/* 버튼 */
.btn {
    width:100%; max-width:320px; height:50px;
    background:#00E87A; color:#000;
    font-size:15px; font-weight:bold;
    border:none; border-radius:8px; cursor:pointer;
    margin-top:10px; transition:opacity 0.2s;
}
.btn:disabled { opacity:0.5; cursor:not-allowed; }
.btn:active { opacity:0.8; }
.security {
    font-size:10px; color:#1A3A28;
    text-align:center; margin-top:10px; max-width:320px;
}

/* 완료 화면 */
.success-screen {
    display:none; flex-direction:column;
    align-items:center; text-align:center;
}
.success-icon { font-size:60px; margin-bottom:16px; }
.success-title { font-size:20px; font-weight:bold; color:#00E87A; margin-bottom:8px; }
.success-sub { font-size:13px; color:#3A5070; }
</style>
</head>
<body>

<!-- 등록 폼 -->
<div id="registerView">
    <h2>카드 등록</h2>
    <div class="sub">Mock PG · 카드 정보는 PG 서버에 직접 전송됩니다</div>

    <div class="card" id="cardPreview">
        <div class="card-brand" id="prevBrand">HYUNDAI</div>
        <div class="card-network" id="prevNetwork">VISA</div>
        <div class="card-chip"></div>
        <div class="card-number" id="prevNumber">•••• •••• •••• ••••</div>
        <div class="card-valid-label">VALID THRU</div>
        <div class="card-expiry" id="prevExpiry">MM/YY</div>
    </div>

    <div class="brands" id="brandList"></div>

    <div class="form-group">
        <label>카드번호</label>
        <input type="tel" id="cardNumber" placeholder="0000 0000 0000 0000"
               maxlength="16" inputmode="numeric"
               oninput="onNumberInput(this.value)">
    </div>
    <div class="row">
        <div class="form-group">
            <label>유효기간</label>
            <input type="tel" id="cardExpiry" placeholder="MM/YY"
                   maxlength="5" oninput="onExpiryInput(this)">
        </div>
        <div class="form-group">
            <label>CVC</label>
            <input type="password" id="cardCvc" placeholder="•••" maxlength="3">
        </div>
    </div>

    <button class="btn" id="submitBtn" onclick="submitCard()">등록하기</button>
    <div class="security">🔒 카드 정보는 암호화되어 전송됩니다</div>
</div>

<!-- 완료 화면 -->
<div class="success-screen" id="successView">
    <div class="success-icon">✅</div>
    <div class="success-title">카드 등록 완료</div>
    <div class="success-sub" id="successSub">잠시 후 자동으로 이동합니다...</div>
</div>

<script>
const ORDER_ID = "__ORDER_ID__";

const brands = [
    { name:"현대카드", short:"HYUNDAI", bg:"#1A1A2E", tc:"#CCCCCC", net:"VISA"   },
    { name:"KB국민",   short:"KB",      bg:"#1A1200", tc:"#FFCC00", net:"MASTER" },
    { name:"신한카드", short:"SHINHAN", bg:"#5C0000", tc:"#FFFFFF", net:"VISA"   },
    { name:"삼성카드", short:"SAMSUNG", bg:"#0A1460", tc:"#FFFFFF", net:"MASTER" },
    { name:"롯데카드", short:"LOTTE",   bg:"#5C0000", tc:"#FFFFFF", net:"VISA"   },
    { name:"우리카드", short:"WOORI",   bg:"#002040", tc:"#FFFFFF", net:"MASTER" },
    { name:"하나카드", short:"HANA",    bg:"#002A1E", tc:"#FFFFFF", net:"VISA"   },
];

let selected = brands[0];

function renderBrands() {
    const el = document.getElementById('brandList');
    el.innerHTML = '';
    brands.forEach(b => {
        const c = document.createElement('div');
        c.className = 'chip' + (b === selected ? ' on' : '');
        c.textContent = b.name;
        if (b === selected) { c.style.background = b.bg; c.style.color = b.tc; }
        c.onclick = () => selectBrand(b);
        el.appendChild(c);
    });
}

function selectBrand(b) {
    selected = b;
    document.getElementById('cardPreview').style.background = b.bg;
    document.getElementById('prevBrand').textContent = b.short;
    document.getElementById('prevBrand').style.color = b.tc;
    document.getElementById('prevNetwork').textContent = b.net;
    renderBrands();
}

function onNumberInput(val) {
    const p = val.padEnd(16, '•');
    document.getElementById('prevNumber').textContent =
        p.slice(0,4)+' '+p.slice(4,8)+' '+p.slice(8,12)+' '+p.slice(12,16);
}

function onExpiryInput(input) {
    let v = input.value.replace('/','');
    if (v.length >= 2) v = v.slice(0,2) + '/' + v.slice(2);
    input.value = v;
    document.getElementById('prevExpiry').textContent = v || 'MM/YY';
}

async function submitCard() {
    const num    = document.getElementById('cardNumber').value.trim();
    const expiry = document.getElementById('cardExpiry').value.trim();
    const cvc    = document.getElementById('cardCvc').value.trim();

    if (num.length < 16)  { alert('카드번호 16자리를 입력하세요'); return; }
    if (!/\\d{2}\\/\\d{2}/.test(expiry)) { alert('유효기간을 MM/YY 형식으로 입력하세요'); return; }
    if (cvc.length < 3)   { alert('CVC 3자리를 입력하세요'); return; }

    const btn = document.getElementById('submitBtn');
    btn.disabled = true;
    btn.textContent = '처리 중...';

    const body = new FormData();
    body.append('order_id',   ORDER_ID);
    body.append('card_number', num);
    body.append('expiry',      expiry);
    body.append('cvc',         cvc);
    body.append('card_brand',  selected.name);

    try {
        const res  = await fetch('/card-register', { method:'POST', body });
        const data = await res.json();

        if (data.success) {
            document.getElementById('registerView').style.display = 'none';
            const sv = document.getElementById('successView');
            sv.style.display = 'flex';
            document.getElementById('successSub').textContent =
                selected.name + ' •••• ' + data.last_four + ' 등록 완료';

            // Android WebView JS 인터페이스 호출 (4개 인자: customerKey, orderId, lastFour, cardBrand)
            if (window.Android) {
                window.Android.onRegistrationComplete(
                    data.customer_key,
                    data.order_id,
                    data.last_four,
                    data.card_brand
                );
            }
        } else {
            alert('등록 실패: ' + (data.message || '알 수 없는 오류'));
            btn.disabled = false;
            btn.textContent = '등록하기';
        }
    } catch(e) {
        alert('오류: ' + e.message);
        btn.disabled = false;
        btn.textContent = '등록하기';
    }
}

// 초기화
selectBrand(brands[0]);
</script>
</body>
</html>"""


# ── 엔드포인트 ──────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def root():
    return HTMLResponse("<h3>Mock PG Server Running ✅<br><a href='/docs'>API Docs</a></h3>")


@app.get("/card-register", response_class=HTMLResponse)
async def card_register_page(order_id: str = "TEST_ORDER"):
    html = CARD_REGISTER_HTML.replace("__ORDER_ID__", order_id)
    return HTMLResponse(content=html)


@app.post("/card-register")
async def process_card(
    order_id:    str = Form(...),
    card_number: str = Form(...),
    expiry:      str = Form(...),
    cvc:         str = Form(...),
    card_brand:  str = Form(...)
):
    # 카드 원번호는 Mock PG 내부에서만 보관 (실 PG는 HSM 저장)
    customer_key = f"ck_{uuid.uuid4().hex[:16]}"
    billing_key  = f"bk_{uuid.uuid4().hex[:16]}"
    last_four    = card_number[-4:] if len(card_number) >= 4 else "****"

    payload = {
        "order_id":     order_id,
        "customer_key": customer_key,
        "billing_key":  billing_key,
        "card_brand":   card_brand,
        "last_four":    last_four,
    }

    # HMAC 서명 — 백엔드와 동일한 4개 필드, 공백 없는 JSON 형식
    hmac_payload = {
        "card_brand":   card_brand,
        "customer_key": customer_key,
        "last_four":    last_four,
        "order_id":     order_id,
    }
    payload_str = json.dumps(hmac_payload, sort_keys=True, separators=(',', ':'))
    sig = hmac.new(
        HMAC_SECRET.encode(),
        payload_str.encode(),
        hashlib.sha256
    ).hexdigest()

    print(f"\n[Mock PG] ✅ 카드 등록 완료")
    print(f"  order_id     : {order_id}")
    print(f"  card_brand   : {card_brand}")
    print(f"  last_four    : {last_four}")
    print(f"  customer_key : {customer_key}")
    print(f"  HMAC         : {sig[:20]}...")

    # 백엔드 웹훅 전송 → customer_key DB 저장
    try:
        async with httpx.AsyncClient() as client:
            await client.post(f"{BACKEND_URL}/webhook/card", json={
                "order_id":     order_id,
                "customer_key": customer_key,
                "card_brand":   card_brand,
                "last_four":    last_four,
                "hmac":         sig,
            }, timeout=3.0)
        print(f"  → 백엔드 웹훅 전송 완료")
    except Exception as e:
        print(f"  → 백엔드 웹훅 실패 (무시): {e}")

    return JSONResponse({
        "success":      True,
        "order_id":     order_id,
        "customer_key": customer_key,
        "last_four":    last_four,
        "card_brand":   card_brand,
        "hmac":         sig,
    })


@app.get("/health")
async def health():
    return {"status": "ok", "service": "Mock PG", "version": "1.0.0"}


# ── 결제 승인 (백엔드 → Mock PG) ─────────────────────────────────────────
from pydantic import BaseModel
import time as _time

class ChargeRequest(BaseModel):
    amount: int
    customer_key: str = "MOCK_CK"
    idempotency_key: str = ""

_processed: dict = {}

@app.post("/charge")
async def charge(req: ChargeRequest):
    """
    백엔드 → 결제 승인 요청

    실제 PG 흐름:
      1. billing_key로 카드 원번호 복호화
      2. VPN 터널 → OpenStack 카드 승인 서비스 전달
      3. 카드사 승인 처리 (1~3초)
      4. 승인번호 발급

    데모: 지연(1.5초)으로 카드사 통신 시뮬레이션
    """
    import asyncio

    # 멱등성 처리 (in-memory — EC2 배포 시 Redis 또는 DB로 교체)
    if req.idempotency_key and req.idempotency_key in _processed:
        print(f"[Mock PG] 중복 요청 — 기존 결과 반환: {req.idempotency_key[:12]}…")
        return _processed[req.idempotency_key]

    # ── VPN → OpenStack 카드 승인 서비스 통신 시뮬레이션 ─────────────────
    print(f"\n[Mock PG] 카드 승인 요청 중...")
    print(f"  customer_key : {req.customer_key[:12]}…")
    print(f"  amount       : {req.amount:,}원")
    print(f"  → [VPN 터널] OpenStack 카드 승인 서비스 전달 중...")
    await asyncio.sleep(1.5)    # 카드사 네트워크 지연 모사 (실제 1~3초)

    tx_id       = f"tx_{uuid.uuid4().hex[:16]}"
    approval_no = f"AP{int(_time.time())}"

    result = {
        "success":      True,
        "tx_id":        tx_id,
        "approval_no":  approval_no,
        "amount":       req.amount,
        "customer_key": req.customer_key
    }

    if req.idempotency_key:
        _processed[req.idempotency_key] = result

    print(f"  ← [카드사 승인 완료] approval_no={approval_no}  tx_id={tx_id}")
    return result
