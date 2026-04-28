"""
barrier_controller.py  –  Webots 주차장 차단기 컨트롤러
=====================================================
MQTT 토픽 carpayin/barrier 를 구독해서
  {"gate": "entry", "action": "open"}  → 입구 차단기 올림
  {"gate": "exit",  "action": "open"}  → 출구 차단기 올림
  {"gate": "entry", "action": "close"} → 입구 차단기 내림  (서버에서 직접 제어 시)
  {"gate": "exit",  "action": "close"} → 출구 차단기 내림

모터 동작:
  올림: setVelocity(2.0) → setPosition(-1.57)
  내림: setVelocity(2.0) → setPosition(0.0)

OPEN_HOLD_MS 동안 열려있다가 자동으로 닫힘 (서버 close 명령 없이도 동작)
"""

from controller import Supervisor
import paho.mqtt.client as mqtt
import json
import threading

# ── 설정 ───────────────────────────────────────────────────────────────────
BROKER_HOST  = "localhost"
BROKER_PORT  = 1883
TOPIC_BARRIER = "carpayin/barrier"

# 모터 디바이스 이름 (Webots world 파일의 name 필드와 일치시킬 것)
MOTOR_ENTRY = "barrier_motor_entry"
MOTOR_EXIT  = "barrier_motor_exit"

# 차단기가 열려있는 시간 (ms). 이후 자동으로 닫힘
OPEN_HOLD_MS = 5000

# 모터 속도
MOTOR_VELOCITY = 2.0

# 위치값
POS_OPEN  = -1.57   # 봉이 올라간 위치
POS_CLOSE =  0.0    # 봉이 내려간 (기본) 위치

# ── 상태 공유 변수 (MQTT 스레드 ↔ 시뮬레이션 루프) ─────────────────────────
_lock        = threading.Lock()
_cmd_queue   = []   # [{"gate": "entry"|"exit", "action": "open"|"close"}, ...]

def _enqueue(gate: str, action: str):
    with _lock:
        _cmd_queue.append({"gate": gate, "action": action})

# ── MQTT 콜백 ──────────────────────────────────────────────────────────────
def _on_connect(client, userdata, flags, rc):
    if rc == 0:
        client.subscribe(TOPIC_BARRIER)
        print(f"[MQTT] 연결 성공 – {TOPIC_BARRIER} 구독 시작")
    else:
        print(f"[MQTT] 연결 실패 rc={rc}")

def _on_message(client, userdata, msg):
    try:
        data   = json.loads(msg.payload.decode())
        gate   = data.get("gate", "entry")
        action = data.get("action", "open")
        print(f"[MQTT] 수신: gate={gate} action={action}")
        _enqueue(gate, action)
    except Exception as e:
        print(f"[MQTT] 메시지 파싱 오류: {e}")

def _start_mqtt():
    client = mqtt.Client()
    client.on_connect = _on_connect
    client.on_message = _on_message
    try:
        client.connect(BROKER_HOST, BROKER_PORT, 60)
        client.loop_forever()
    except Exception as e:
        print(f"[MQTT] 브로커 연결 실패 – 차단기는 수동 모드: {e}")

# ── Webots 초기화 ──────────────────────────────────────────────────────────
robot    = Supervisor()
timestep = int(robot.getBasicTimeStep())

motor_entry = robot.getDevice(MOTOR_ENTRY)
motor_exit  = robot.getDevice(MOTOR_EXIT)

for motor in (motor_entry, motor_exit):
    if motor:
        motor.setVelocity(MOTOR_VELOCITY)
        motor.setPosition(POS_CLOSE)   # 시작 시 닫힌 상태

# ── MQTT 백그라운드 스레드 시작 ────────────────────────────────────────────
mqtt_thread = threading.Thread(target=_start_mqtt, daemon=True)
mqtt_thread.start()

# ── 열림 타이머 상태 ───────────────────────────────────────────────────────
# { "entry": 남은ms, "exit": 남은ms }
open_timer = {"entry": 0, "exit": 0}

def _get_motor(gate: str):
    return motor_entry if gate == "entry" else motor_exit

def _open_gate(gate: str):
    m = _get_motor(gate)
    if m is None:
        print(f"[차단기] 모터를 찾을 수 없음: {gate}")
        return
    m.setVelocity(MOTOR_VELOCITY)
    m.setPosition(POS_OPEN)
    open_timer[gate] = OPEN_HOLD_MS
    print(f"[차단기] {gate} 열림 ↑  ({OPEN_HOLD_MS/1000}초 후 자동 닫힘)")

def _close_gate(gate: str):
    m = _get_motor(gate)
    if m is None:
        return
    m.setVelocity(MOTOR_VELOCITY)
    m.setPosition(POS_CLOSE)
    open_timer[gate] = 0
    print(f"[차단기] {gate} 닫힘 ↓")

# ── 메인 시뮬레이션 루프 ───────────────────────────────────────────────────
print("[차단기] 컨트롤러 시작 – MQTT 명령 대기 중...")

while robot.step(timestep) != -1:

    # 1) MQTT 큐에서 명령 꺼내기
    with _lock:
        commands = _cmd_queue[:]
        _cmd_queue.clear()

    for cmd in commands:
        gate   = cmd["gate"]
        action = cmd["action"]
        if action == "open":
            _open_gate(gate)
        elif action == "close":
            _close_gate(gate)

    # 2) 자동 닫힘 타이머
    for gate in ("entry", "exit"):
        if open_timer[gate] > 0:
            open_timer[gate] -= timestep
            if open_timer[gate] <= 0:
                _close_gate(gate)
