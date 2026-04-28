from pydantic import BaseModel
from typing import Optional

# ── 인증 ───────────────────────────────────────────────────────────────────
class RegisterVehicleRequest(BaseModel):
    vin: str
    cert_hash: str = "MOCK_CERT_HASH"

class ConfirmPlateRequest(BaseModel):
    vin: str
    plate: str

class CardWebhookRequest(BaseModel):
    order_id: str
    customer_key: str
    last_four: str
    card_brand: str
    hmac: str

# ── 입차 ───────────────────────────────────────────────────────────────────
class PreNotifyRequest(BaseModel):
    vin: str
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
class FeeQueryRequest(BaseModel):
    session_id: str

class PaymentRequest(BaseModel):
    session_id: str
    amount: int

# ── 주차장 PMS paid 전달 ───────────────────────────────────────────────────
class PaidNotifyRequest(BaseModel):
    plate: str
    lot_id: str
    tx_id: str
