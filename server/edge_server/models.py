from pydantic import BaseModel
from typing import Optional

# ── 요청 모델 ──────────────────────────────────────────────────────────────

class PreNotifyRequest(BaseModel):
    vin: str
    plate: str
    lot_id: str
    trigger: str = "geofence"   # "geofence" or "navi"

class EntryEventRequest(BaseModel):
    plate: str
    lot_id: str
    event_id: str               # 멱등성 키
    timestamp: int              # ms 단위

class FeeQueryRequest(BaseModel):
    session_id: str
    lot_id: str

class PaymentRequest(BaseModel):
    session_id: str
    amount: int

# ── 응답 모델 ──────────────────────────────────────────────────────────────

class PreNotifyResponse(BaseModel):
    status: str
    lot_id: str
    plate: str

class EntryEventResponse(BaseModel):
    session_id: str
    lot_id: str
    plate: str
    entry_time: str

class FeeResponse(BaseModel):
    session_id: str
    lot_id: str
    lot_name: str
    amount: int
    duration_minutes: int

class PaymentResponse(BaseModel):
    tx_id: str
    approval_no: str
    amount: int
    lot_id: str
