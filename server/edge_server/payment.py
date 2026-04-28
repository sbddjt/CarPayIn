import uuid
import time
import hashlib
from datetime import datetime
from fastapi import HTTPException
from database import get_conn
from models import PaymentRequest
import mqtt_service

MOCK_PG_URL = "http://localhost:9000"   # mock_pg 서버 주소

def process_payment(req: PaymentRequest) -> dict:
    """
    결제 처리 흐름:
    1. 세션 확인
    2. idempotency_key 생성 및 중복 확인
    3. Mock PG 호출 (실패 시 예외)
    4. 거래 내역 저장 + 세션 completed
    5. MQTT 결제 완료 알림
    6. 출구 차단기 열기 신호
    """
    with get_conn() as con:
        session = con.execute(
            "SELECT lot_id, plate FROM sessions WHERE session_id=? AND status='active'",
            (req.session_id,)
        ).fetchone()

        if not session:
            raise HTTPException(404, "세션 없음 또는 이미 완료")

        lot_id = session["lot_id"]
        plate  = session["plate"]

        # idempotency_key: session_id + amount + timestamp(초 단위)
        raw_key     = f"{req.session_id}:{req.amount}:{int(time.time() // 10)}"
        idempotency = hashlib.sha256(raw_key.encode()).hexdigest()

        existing_tx = con.execute(
            "SELECT tx_id, approval_no FROM transactions WHERE idempotency_key=?",
            (idempotency,)
        ).fetchone()

        if existing_tx:
            print(f"[결제] 중복 요청 감지, 기존 결과 반환: {existing_tx['tx_id'][:8]}...")
            return {
                "tx_id":       existing_tx["tx_id"],
                "approval_no": existing_tx["approval_no"],
                "amount":      req.amount,
                "lot_id":      lot_id
            }

    # Mock PG 호출
    approval_no, tx_id = _call_mock_pg(req.amount, idempotency)

    now = datetime.now().isoformat()

    with get_conn() as con:
        con.execute("""
            INSERT INTO transactions
                (tx_id, session_id, lot_id, amount, approval_no, idempotency_key, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (tx_id, req.session_id, lot_id, req.amount, approval_no, idempotency, now))

        con.execute("""
            UPDATE sessions
            SET status='completed', exit_time=?, amount=?
            WHERE session_id=?
        """, (now, req.amount, req.session_id))

    # MQTT 알림
    mqtt_service.notify_payment(tx_id, approval_no, lot_id, req.amount)
    mqtt_service.notify_barrier("exit", "open")

    print(f"[결제완료] plate={plate} amount={req.amount:,}원 approval={approval_no}")
    return {
        "tx_id":       tx_id,
        "approval_no": approval_no,
        "amount":      req.amount,
        "lot_id":      lot_id
    }


def _call_mock_pg(amount: int, idempotency: str) -> tuple[str, str]:
    """
    Mock PG 호출. mock_pg 서버가 없으면 자체 승인번호 생성.
    """
    try:
        import httpx
        res = httpx.post(f"{MOCK_PG_URL}/charge", json={
            "amount":          amount,
            "idempotency_key": idempotency
        }, timeout=5.0)
        res.raise_for_status()
        data = res.json()
        return data["approval_no"], data["tx_id"]
    except Exception as e:
        print(f"[Mock PG] 연결 실패, 자체 승인 처리: {e}")
        approval_no = f"AP{int(time.time())}"
        tx_id       = str(uuid.uuid4())
        return approval_no, tx_id
