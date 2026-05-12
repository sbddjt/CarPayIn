"""
Mock 아이파킹 PMS (Parking Management System)
- 번호판 사전 등록 수신
- LPR 입차 시뮬레이션
- 차단기 상태 관리
- paid 상태 수신 후 출구 차단기 제어
"""

from fastapi import FastAPI, HTTPException
from contextlib import asynccontextmanager
from pydantic import BaseModel
from datetime import datetime
import sqlite3, uuid, httpx, os, json, threading
from contextlib import contextmanager
import paho.mqtt.client as mqtt

BACKEND_URL = os.environ.get("BACKEND_URL",  "http://localhost:8000")
MQTT_HOST   = os.environ.get("MQTT_HOST",    "localhost")
MQTT_PORT   = int(os.environ.get("MQTT_PORT", "1883"))
DB_PATH     = "pms.db"

# ── MQTT (Webots 차단기 제어) ─────────────────────────────────────────────
_mqtt_client    = mqtt.Client()
_mqtt_connected = False

def _on_mqtt_connect(client, userdata, flags, rc):
    global _mqtt_connected
    _mqtt_connected = (rc == 0)
    if _mqtt_connected:
        print(f"[PMS MQTT] 브로커 연결 성공 ({MQTT_HOST}:{MQTT_PORT})")
    else:
        print(f"[PMS MQTT] 연결 실패 rc={rc}")

_mqtt_client.on_connect = _on_mqtt_connect

def _mqtt_start():
    try:
        _mqtt_client.connect(MQTT_HOST, MQTT_PORT, 60)
        _mqtt_client.loop_forever()
    except Exception as e:
        print(f"[PMS MQTT] 브로커 없음, 차단기 MQTT 비활성화: {e}")

def _publish_barrier(gate: str, action: str):
    """Webots 차단기 컨트롤러로 MQTT 명령 발행"""
    if not _mqtt_connected:
        print(f"[PMS MQTT 스킵] barrier gate={gate} action={action}")
        return
    _mqtt_client.publish(
        "carpayin/barrier",
        json.dumps({"gate": gate, "action": action}, ensure_ascii=False)
    )
    print(f"[PMS MQTT] barrier gate={gate} action={action} 발행")

# ── DB ────────────────────────────────────────────────────────────────────
@contextmanager
def get_conn():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()

def init_db():
    with get_conn() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS registered_plates (
                plate      TEXT,
                lot_id     TEXT,
                created_at TEXT,
                PRIMARY KEY (plate, lot_id)
            );

            CREATE TABLE IF NOT EXISTS parking_records (
                record_id  TEXT PRIMARY KEY,
                plate      TEXT,
                lot_id     TEXT,
                entry_time TEXT,
                status     TEXT DEFAULT 'parked'
            );

            CREATE TABLE IF NOT EXISTS barrier_state (
                gate    TEXT PRIMARY KEY,
                status  TEXT DEFAULT 'closed'
            );
        """)
        # 차단기 초기값
        con.execute("INSERT OR IGNORE INTO barrier_state VALUES ('entry','closed')")
        con.execute("INSERT OR IGNORE INTO barrier_state VALUES ('exit','closed')")

# ── 앱 시작 ────────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    threading.Thread(target=_mqtt_start, daemon=True).start()
    print("[PMS] Mock 아이파킹 PMS 시작 (포트 8001)")
    yield

app = FastAPI(
    title="Mock 아이파킹 PMS",
    description="주차장 현장 서버 (LPR + 차단기 + 번호판 관리)",
    version="1.0.0",
    lifespan=lifespan
)

# ── 모델 ──────────────────────────────────────────────────────────────────
class RegisterPlateRequest(BaseModel):
    plate: str
    lot_id: str

class LprEventRequest(BaseModel):
    plate: str
    lot_id: str
    gate: str = "entry"   # entry | exit

class PaidRequest(BaseModel):
    plate: str
    lot_id: str
    tx_id: str

# ── 엔드포인트 ────────────────────────────────────────────────────────────

@app.get("/", tags=["Health"])
def health():
    return {"status": "ok", "service": "Mock 아이파킹 PMS"}


@app.post("/register-plate", tags=["사전등록"])
def register_plate(req: RegisterPlateRequest):
    """백엔드 → 번호판 사전 등록"""
    with get_conn() as con:
        con.execute("""
            INSERT OR REPLACE INTO registered_plates (plate, lot_id, created_at)
            VALUES (?, ?, ?)
        """, (req.plate, req.lot_id, datetime.now().isoformat()))

    print(f"[PMS] 번호판 사전 등록: {req.plate} @ {req.lot_id}")
    return {"status": "ok", "plate": req.plate}


@app.post("/lpr", tags=["LPR"])
async def lpr_event(req: LprEventRequest):
    """
    LPR 카메라 감지 시뮬레이션 (Webots 센서 or 수동 트리거)
    - 입구: 모든 차량 차단기 즉시 열기 → 사전 등록 차량만 백엔드 웹훅
    - 출구: paid 상태 확인 후 차단기 열기
    """
    if req.gate == "entry":
        # 모든 차량 차단기 즉시 오픈 (현장 처리 — 클라우드 응답 대기 없음)
        _set_barrier("entry", "open")
        _publish_barrier("entry", "open")   # Webots 차단기 제어
        print(f"[PMS LPR] 입구 감지: {req.plate} → 차단기 즉시 개방")

        # 사전 등록 차량인지 확인
        with get_conn() as con:
            pre = con.execute(
                "SELECT plate FROM registered_plates WHERE plate=? AND lot_id=?",
                (req.plate, req.lot_id)
            ).fetchone()

        if pre:
            # 주차 기록 생성
            with get_conn() as con:
                con.execute("""
                    INSERT OR IGNORE INTO parking_records (record_id, plate, lot_id, entry_time, status)
                    VALUES (?, ?, ?, ?, 'parked')
                """, (str(uuid.uuid4()), req.plate, req.lot_id, datetime.now().isoformat()))

            # 백엔드로 입차 이벤트 전송
            try:
                async with httpx.AsyncClient() as client:
                    await client.post(f"{BACKEND_URL}/webhook/entry", json={
                        "event_id":  str(uuid.uuid4()),
                        "plate":     req.plate,
                        "lot_id":    req.lot_id,
                        "timestamp": int(datetime.now().timestamp() * 1000)
                    }, timeout=5.0)
            except Exception as e:
                print(f"[PMS] 백엔드 웹훅 실패: {e}")

            return {"status": "registered", "barrier": "open", "plate": req.plate}
        else:
            print(f"[PMS] 미등록 차량 통과: {req.plate}")
            return {"status": "unregistered", "barrier": "open", "plate": req.plate}

    elif req.gate == "exit":
        # paid 상태 확인
        with get_conn() as con:
            record = con.execute(
                "SELECT status FROM parking_records WHERE plate=? AND lot_id=? ORDER BY entry_time DESC LIMIT 1",
                (req.plate, req.lot_id)
            ).fetchone()

        if record and record["status"] == "paid":
            _set_barrier("exit", "open")
            _publish_barrier("exit", "open")    # Webots 차단기 제어
            print(f"[PMS LPR] 출구 감지: {req.plate} → paid 확인 → 차단기 개방")
            return {"status": "paid", "barrier": "open"}
        else:
            print(f"[PMS LPR] 출구 감지: {req.plate} → 미결제 → 차단기 유지")
            return {"status": "unpaid", "barrier": "closed", "message": "현장 무인정산기 이용"}

    raise HTTPException(400, "gate는 entry 또는 exit")


@app.post("/paid", tags=["결제"])
def paid_notify(req: PaidRequest):
    """백엔드 → 결제 완료 통보 → 주차 기록 paid 처리"""
    with get_conn() as con:
        con.execute("""
            UPDATE parking_records SET status='paid'
            WHERE plate=? AND lot_id=? AND status='parked'
        """, (req.plate, req.lot_id))

    print(f"[PMS] paid 처리: {req.plate} tx={req.tx_id}")
    return {"status": "ok", "plate": req.plate}


@app.get("/barrier", tags=["차단기"])
def get_barrier():
    """차단기 현재 상태"""
    with get_conn() as con:
        rows = con.execute("SELECT * FROM barrier_state").fetchall()
    return {r["gate"]: r["status"] for r in rows}


@app.post("/barrier/reset", tags=["차단기"])
def reset_barrier():
    """차단기 닫기 (차량 통과 후 리셋)"""
    _set_barrier("entry", "closed")
    _set_barrier("exit",  "closed")
    return {"status": "closed"}


@app.get("/records", tags=["디버그"])
def list_records():
    with get_conn() as con:
        rows = con.execute(
            "SELECT * FROM parking_records ORDER BY entry_time DESC LIMIT 30"
        ).fetchall()
    return [dict(r) for r in rows]


@app.get("/registered", tags=["디버그"])
def list_registered():
    with get_conn() as con:
        rows = con.execute("SELECT * FROM registered_plates").fetchall()
    return [dict(r) for r in rows]


@app.delete("/debug/reset", tags=["디버그"])
def reset_all():
    with get_conn() as con:
        con.execute("DELETE FROM registered_plates")
        con.execute("DELETE FROM parking_records")
        con.execute("UPDATE barrier_state SET status='closed'")
    return {"status": "초기화 완료"}


# ── 내부 함수 ─────────────────────────────────────────────────────────────
def _set_barrier(gate: str, status: str):
    with get_conn() as con:
        con.execute(
            "UPDATE barrier_state SET status=? WHERE gate=?",
            (status, gate)
        )
