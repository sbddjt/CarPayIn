from fastapi import FastAPI
from contextlib import asynccontextmanager
from database import init_db
from models import (
    PreNotifyRequest, EntryEventRequest, PaymentRequest,
    PreNotifyResponse, EntryEventResponse, FeeResponse, PaymentResponse
)
import parking
import payment
import mqtt_service

@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    mqtt_service.start()
    print("[서버] CarPayIn Edge Server 시작")
    yield
    print("[서버] 종료")

app = FastAPI(
    title="CarPayIn Edge Server",
    description="하이브리드 클라우드 인카 통합 페이먼트 엣지 서버",
    version="1.0.0",
    lifespan=lifespan
)

# ── 상태 확인 ──────────────────────────────────────────────────────────────

@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "service": "CarPayIn Edge Server"}


# ── 입차 흐름 ──────────────────────────────────────────────────────────────

@app.post("/pre-notify", tags=["입차"])
def pre_notify(req: PreNotifyRequest):
    """
    앱 → 지오펜스 진입 or 내비 목적지 설정 시 사전 알림
    """
    return parking.pre_notify(req)


@app.post("/entry", tags=["입차"])
def entry_event(req: EntryEventRequest):
    """
    Mock LPR 카메라 → 차량 입차 감지 이벤트
    사전 등록된 번호판만 세션 생성, 차단기 열기 신호 발송
    """
    return parking.handle_entry(req)


# ── 요금 / 결제 흐름 ────────────────────────────────────────────────────────

@app.get("/fee/{session_id}", tags=["결제"])
def get_fee(session_id: str):
    """
    앱 → 현재 주차 요금 조회 (시동 ON 시 자동 호출)
    """
    return parking.query_fee(session_id)


@app.post("/payment", tags=["결제"])
def process_payment(req: PaymentRequest):
    """
    앱 → 결제 처리 요청
    idempotency_key로 중복 결제 방지, Mock PG 호출, 출구 차단기 열기
    """
    return payment.process_payment(req)


# ── 디버그 ─────────────────────────────────────────────────────────────────

@app.get("/sessions", tags=["디버그"])
def list_sessions():
    """전체 세션 목록 조회"""
    from database import get_conn
    with get_conn() as con:
        rows = con.execute(
            "SELECT * FROM sessions ORDER BY entry_time DESC LIMIT 30"
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/transactions", tags=["디버그"])
def list_transactions():
    """전체 거래 내역 조회"""
    from database import get_conn
    with get_conn() as con:
        rows = con.execute(
            "SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 30"
        ).fetchall()
    return [dict(r) for r in rows]


@app.delete("/sessions/reset", tags=["디버그"])
def reset_all():
    """전체 데이터 초기화 (테스트용)"""
    from database import get_conn
    with get_conn() as con:
        con.execute("DELETE FROM sessions")
        con.execute("DELETE FROM transactions")
        con.execute("DELETE FROM pre_notify")
    return {"status": "초기화 완료"}
