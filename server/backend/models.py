from pydantic import BaseModel

# ── 인증 ───────────────────────────────────────────────────────────────────

class ConfirmCarRequest(BaseModel):
    """AAOS app confirms the selected Hyundai carId with the session VIN hash."""
    vin_hash: str
    car_id: str

# ── 입차 ───────────────────────────────────────────────────────────────────

class PreNotifyRequest(BaseModel):
    car_id: str
    plate: str
    lot_id: str
    trigger: str = "geofence"   # geofence | navi

class EntryWebhookRequest(BaseModel):
    """아이파킹 PMS → 백엔드 입차 이벤트"""
    event_id: str
    plate: str
    lot_id: str
    timestamp: int

# ── 결제 ───────────────────────────────────────────────────────────────────

class PaymentRequest(BaseModel):
    session_id: str
    amount: int

# ── 주차장 PMS paid 전달 ───────────────────────────────────────────────────

class PaidNotifyRequest(BaseModel):
    plate: str
    lot_id: str
    tx_id: str
