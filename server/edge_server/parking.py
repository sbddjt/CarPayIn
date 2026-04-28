import uuid
from datetime import datetime
from fastapi import HTTPException
from database import get_conn
from models import PreNotifyRequest, EntryEventRequest
import mqtt_service

# ── 요금 정책 ──────────────────────────────────────────────────────────────
def calc_fee(entry_time: str) -> tuple[int, int]:
    """(금액, 분) 반환. 30분당 500원, 최소 500원"""
    entry   = datetime.fromisoformat(entry_time)
    elapsed = (datetime.now() - entry).total_seconds()
    minutes = int(elapsed / 60)
    amount  = max(500, (minutes // 30 + 1) * 500)
    return amount, minutes

# ── 사전 알림 ──────────────────────────────────────────────────────────────
def pre_notify(req: PreNotifyRequest) -> dict:
    """
    앱 지오펜스 진입 or 내비 목적지 설정 시 호출
    → Redis 대신 SQLite pre_notify 테이블에 TTL 없이 저장
    """
    now = datetime.now().isoformat()
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO pre_notify (plate, lot_id, created_at)
            VALUES (?, ?, ?)
        """, (req.plate, req.lot_id, now))

    print(f"[사전알림] plate={req.plate} lot={req.lot_id} trigger={req.trigger}")
    return {"status": "ok", "lot_id": req.lot_id, "plate": req.plate}

# ── 입차 이벤트 ────────────────────────────────────────────────────────────
def handle_entry(req: EntryEventRequest) -> dict:
    """
    Mock LPR 카메라 → 입차 이벤트
    - 사전 등록 차량만 세션 생성
    - 멱등성: event_id + plate + lot_id 조합으로 중복 방지
    """
    with get_conn() as con:
        # 사전 등록 확인
        pre = con.execute(
            "SELECT plate FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        ).fetchone()

        if not pre:
            print(f"[입차] 미등록 차량 통과: {req.plate}")
            return {"status": "unregistered", "plate": req.plate}

        # 중복 이벤트 확인
        existing = con.execute(
            "SELECT session_id FROM sessions WHERE plate=? AND lot_id=? AND status='active'",
            (req.plate, req.lot_id)
        ).fetchone()

        if existing:
            print(f"[입차] 중복 이벤트 무시: {req.plate}")
            return {"status": "duplicate", "session_id": existing["session_id"]}

        session_id = str(uuid.uuid4())
        entry_time = datetime.now().isoformat()

        con.execute("""
            INSERT INTO sessions (session_id, plate, lot_id, entry_time, status)
            VALUES (?, ?, ?, ?, 'active')
        """, (session_id, req.plate, req.lot_id, entry_time))

        # 사전알림 삭제
        con.execute(
            "DELETE FROM pre_notify WHERE plate=? AND lot_id=?",
            (req.plate, req.lot_id)
        )

    # 앱으로 입차 확정 알림
    mqtt_service.notify_entry(session_id, req.lot_id, req.plate, entry_time)
    mqtt_service.notify_barrier("entry", "open")

    print(f"[입차확정] plate={req.plate} session={session_id[:8]}...")
    return {
        "status": "confirmed",
        "session_id": session_id,
        "lot_id": req.lot_id,
        "plate": req.plate,
        "entry_time": entry_time
    }

# ── 요금 조회 ──────────────────────────────────────────────────────────────
def query_fee(session_id: str) -> dict:
    with get_conn() as con:
        row = con.execute(
            "SELECT lot_id, entry_time FROM sessions WHERE session_id=? AND status='active'",
            (session_id,)
        ).fetchone()

    if not row:
        raise HTTPException(404, "활성 세션 없음")

    amount, minutes = calc_fee(row["entry_time"])
    return {
        "session_id":       session_id,
        "lot_id":           row["lot_id"],
        "lot_name":         f"{row['lot_id']} 주차장",
        "amount":           amount,
        "duration_minutes": minutes
    }
