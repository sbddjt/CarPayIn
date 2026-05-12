import paho.mqtt.client as mqtt
import json

BROKER_HOST = "localhost"
BROKER_PORT = 1883

_client = mqtt.Client()
_connected = False

def _on_connect(client, userdata, flags, rc):
    global _connected
    _connected = (rc == 0)
    print(f"[MQTT] {'연결 성공' if _connected else f'연결 실패 rc={rc}'}")

_client.on_connect = _on_connect

def start():
    try:
        _client.connect(BROKER_HOST, BROKER_PORT, 60)
        _client.loop_start()
    except Exception as e:
        print(f"[MQTT] 브로커 없음, 알림 비활성화: {e}")

def publish(topic: str, payload: dict):
    if not _connected:
        print(f"[MQTT 스킵] {topic}: {payload}")
        return
    _client.publish(topic, json.dumps(payload, ensure_ascii=False))
    print(f"[MQTT 발행] {topic}")

def notify_entry(session_id: str, lot_id: str, plate: str, entry_time: str, vin: str = ""):
    """
    앱 구독 토픽: parking/confirmed/{vin}
    payload: { lot_id, session_id, lot_name }
    """
    topic = f"parking/confirmed/{vin}" if vin else "parking/confirmed/+"
    publish(topic, {
        "session_id": session_id,
        "lot_id":     lot_id,
        "lot_name":   f"{lot_id} 주차장",
        "plate":      plate,
        "entry_time": entry_time
    })

def notify_payment(tx_id: str, approval_no: str, lot_id: str, amount: int, vin: str = ""):
    """
    앱 구독 토픽: payment/complete/{vin}
    payload: { transaction_id, approval_number, lot_id, amount }
    """
    topic = f"payment/complete/{vin}" if vin else "payment/complete/+"
    publish(topic, {
        "transaction_id":  tx_id,
        "approval_number": approval_no,
        "lot_id":          lot_id,
        "amount":          amount
    })
