import paho.mqtt.client as mqtt
import json
import threading

BROKER_HOST = "localhost"
BROKER_PORT = 1883

# 토픽 상수
TOPIC_ENTRY    = "carpayin/entry"
TOPIC_PAYMENT  = "carpayin/payment"
TOPIC_BARRIER  = "carpayin/barrier"

_client = mqtt.Client()
_connected = False

def _on_connect(client, userdata, flags, rc):
    global _connected
    if rc == 0:
        _connected = True
        print("[MQTT] 브로커 연결 성공")
    else:
        print(f"[MQTT] 연결 실패 rc={rc}")

def _on_disconnect(client, userdata, rc):
    global _connected
    _connected = False
    print("[MQTT] 연결 끊김")

_client.on_connect    = _on_connect
_client.on_disconnect = _on_disconnect

def start():
    try:
        _client.connect(BROKER_HOST, BROKER_PORT, 60)
        _client.loop_start()
    except Exception as e:
        print(f"[MQTT] 브로커 없음, 알림 비활성화: {e}")

def publish(topic: str, payload: dict):
    if not _connected:
        print(f"[MQTT] 미연결 — 전송 스킵: {topic} {payload}")
        return
    _client.publish(topic, json.dumps(payload, ensure_ascii=False))

# ── 편의 함수 ──────────────────────────────────────────────────────────────

def notify_entry(session_id: str, lot_id: str, plate: str, entry_time: str):
    publish(TOPIC_ENTRY, {
        "session_id": session_id,
        "lot_id":     lot_id,
        "plate":      plate,
        "entry_time": entry_time
    })

def notify_payment(tx_id: str, approval_no: str, lot_id: str, amount: int):
    publish(TOPIC_PAYMENT, {
        "tx_id":       tx_id,
        "approval_no": approval_no,
        "lot_id":      lot_id,
        "amount":      amount
    })

def notify_barrier(gate: str, action: str):
    """gate: 'entry' | 'exit',  action: 'open' | 'close'"""
    publish(TOPIC_BARRIER, {"gate": gate, "action": action})
