"""
CarPayIn 차량 컨트롤러 - 수동 주행

  ↑  : 전진 가속
  ↓  : 감속 / 후진
  ←→ : 조향

LPR 자동 트리거:
  입차 게이트(X≈49.78) 에서 4m 이내 진입 시 → 입차 LPR 자동 발사
  결제 완료 후 출차 게이트(X≈49.73) 에서 4m 이내 진입 시 → 출차 LPR 자동 발사

상태:
  driving       → 자유 주행 (입차 게이트 감시 중)
  at_entry_gate → 입차 LPR 발사 + 차단기 열림 대기
  inside        → 주차장 내부 자유 주행 + PMS 결제 완료 폴링
  at_exit_gate  → 출차 LPR 발사 + 차단기 열림 대기
  done          → 완료
"""
import math
import json
import os
import urllib.request
from datetime import datetime, timezone

# ── Webots 드라이버 임포트 ────────────────────────────────────────────────
try:
    from vehicle import Driver
    robot = Driver()
    USING_DRIVER = True
except Exception:
    from controller import Robot
    robot = Robot()
    USING_DRIVER = False

timestep = int(robot.getBasicTimeStep())

def step_robot():
    return robot.step() if USING_DRIVER else robot.step(timestep)

# ── .env 로드 ─────────────────────────────────────────────────────────────
_here = os.path.dirname(os.path.abspath(__file__))
_env_path = os.path.join(_here, ".env")
if os.path.exists(_env_path):
    with open(_env_path, encoding="utf-8") as _f:
        for _line in _f:
            _line = _line.strip()
            if not _line or _line.startswith("#") or "=" not in _line:
                continue
            _k, _, _v = _line.partition("=")
            _k, _v = _k.strip(), _v.strip().strip('"').strip("'")
            if _k and _k not in os.environ:
                os.environ[_k] = _v

PMS_URL = os.environ.get("PARKING_PMS_URL", "http://localhost:8001")
PLATE   = os.environ.get("WEBOTS_PLATE",    "12가3456")
LOT_ID  = os.environ.get("WEBOTS_LOT_ID",   "LOT_TEST_01")

# ── 게이트 위치 ───────────────────────────────────────────────────────────
ENTRY_GATE_X   = 49.78
EXIT_GATE_X    = 49.73
GATE_TRIGGER_D = 4.0    # m — 이 거리 안에 들어오면 LPR 자동 트리거

# ── 주행 파라미터 ─────────────────────────────────────────────────────────
MAX_SPEED      = 20.0   # km/h
ACCEL          = 4.0    # km/h per step
BRAKE          = 8.0    # km/h per step
MAX_STEER      = 0.4    # rad
STEER_STEP     = 0.06   # rad per step (조향 변화량)
STEER_RETURN   = 0.03   # rad per step (중립 복귀 속도)

# ── 폴링 파라미터 ─────────────────────────────────────────────────────────
PARK_POLL_INTERVAL    = 5.0   # s
BARRIER_POLL_INTERVAL = 0.5   # s
BARRIER_TIMEOUT       = 15.0  # s
EXIT_RETRY_INTERVAL   = 8.0   # s

ENTRY_BARRIER_PORT = 8100
EXIT_BARRIER_PORT  = 8101


# ── HTTP 헬퍼 ─────────────────────────────────────────────────────────────
def _get(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"[VC] GET {url}: {e}", flush=True)
        return None


def _post(url, data, timeout=10):
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"[VC] POST {url}: {e}", flush=True)
        return None


def is_barrier_open(port):
    data = _get(f"http://localhost:{port}/status")
    return bool(data and data.get("is_open"))


def get_pms_status():
    data = _get(f"{PMS_URL}/parking/session-status?plate={PLATE}&lot_id={LOT_ID}")
    return data.get("status") if data else None


def trigger_entry():
    t = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    print(f"[LPR] 입차 → plate={PLATE}", flush=True)
    res = _post(f"{PMS_URL}/lpr/entry", {"plate": PLATE, "lot_id": LOT_ID, "entry_time": t})
    print(f"[LPR] 입차 응답: {res}", flush=True)
    return res


def trigger_exit():
    print(f"[LPR] 출차 → plate={PLATE}", flush=True)
    res = _post(f"{PMS_URL}/lpr/exit", {"plate": PLATE, "lot_id": LOT_ID})
    print(f"[LPR] 출차 응답: {res}", flush=True)
    return res


# ── 차량 제어 ─────────────────────────────────────────────────────────────
def drive(speed, steer):
    if USING_DRIVER:
        robot.setCruisingSpeed(speed)
        robot.setSteeringAngle(steer)


def stop():
    drive(0, 0)


# ── 센서 / 키보드 초기화 ──────────────────────────────────────────────────
gps = robot.getDevice("gps")
if gps:
    gps.enable(timestep)

keyboard = robot.getKeyboard()
keyboard.enable(timestep)

translation_field = None
try:
    self_node = robot.getSelf()
    translation_field = self_node.getField("translation")
except Exception:
    pass

# ── 상태 초기화 ───────────────────────────────────────────────────────────
state   = "driving"   # driving | at_entry_gate | inside | at_exit_gate | done
paid    = False       # 결제 완료 여부

# 주행 변수
cur_speed = 0.0
cur_steer = 0.0
wx, wy    = 70.71, 1.05

# at_entry_gate 변수
entry_lpr_sent = False
entry_wait_t   = 0.0
entry_poll_t   = 0.0

# inside 변수
park_poll_t    = 0.0

# at_exit_gate 변수
exit_lpr_sent  = False
exit_lpr_res   = None
exit_wait_t    = 0.0
exit_poll_t    = 0.0
exit_retry_t   = 0.0

print(f"[VC] 수동 주행 모드 시작 — plate={PLATE}, lot={LOT_ID}, PMS={PMS_URL}", flush=True)
print(f"[VC] 조작: ↑전진  ↓후진/감속  ←→조향", flush=True)
print(f"[VC] 상태: {state}", flush=True)


# ════════════════════════════════════════════════════════════════════════════
while step_robot() != -1:
    dt = timestep / 1000.0

    # ── GPS 위치 갱신 ────────────────────────────────────────────────────
    if gps:
        vals = gps.getValues()
        wx, wy = vals[0], vals[1]
    elif translation_field:
        cur = translation_field.getSFVec3f()
        wx, wy = cur[0], cur[1]

    # ── 키 입력 수집 ─────────────────────────────────────────────────────
    keys = set()
    k = keyboard.getKey()
    while k != -1:
        keys.add(k)
        k = keyboard.getKey()

    UP    = keyboard.UP
    DOWN  = keyboard.DOWN
    LEFT  = keyboard.LEFT
    RIGHT = keyboard.RIGHT

    # ════════════════════════════════════════════════════════════════════
    # 수동 주행 상태: driving / inside
    # ════════════════════════════════════════════════════════════════════
    if state in ("driving", "inside"):

        # 속도 계산
        if UP in keys:
            cur_speed = min(cur_speed + ACCEL * dt, MAX_SPEED)
        elif DOWN in keys:
            cur_speed = max(cur_speed - BRAKE * dt, -MAX_SPEED * 0.5)
        else:
            # 아무 키 없으면 관성 감속
            if cur_speed > 0:
                cur_speed = max(0.0, cur_speed - BRAKE * 0.5 * dt)
            elif cur_speed < 0:
                cur_speed = min(0.0, cur_speed + BRAKE * 0.5 * dt)

        # 조향 계산
        if LEFT in keys:
            cur_steer = max(cur_steer - STEER_STEP, -MAX_STEER)
        elif RIGHT in keys:
            cur_steer = min(cur_steer + STEER_STEP, MAX_STEER)
        else:
            # 키 없으면 핸들 중립 복귀
            if cur_steer > 0:
                cur_steer = max(0.0, cur_steer - STEER_RETURN)
            elif cur_steer < 0:
                cur_steer = min(0.0, cur_steer + STEER_RETURN)

        drive(cur_speed, cur_steer)

        # ── 입차 게이트 근접 자동 트리거 ────────────────────────────
        if state == "driving":
            if abs(wx - ENTRY_GATE_X) < GATE_TRIGGER_D:
                print(f"[VC] 입차 게이트 감지 (X={wx:.1f}) → LPR 입차", flush=True)
                entry_lpr_sent = False
                entry_wait_t   = 0.0
                entry_poll_t   = 0.0
                state = "at_entry_gate"
                print(f"[VC] 상태: {state}", flush=True)

        # ── 출차 게이트 근접 자동 트리거 (결제 완료 후만) ───────────
        elif state == "inside" and paid:
            if abs(wx - EXIT_GATE_X) < GATE_TRIGGER_D:
                print(f"[VC] 출차 게이트 감지 (X={wx:.1f}) → LPR 출차", flush=True)
                exit_lpr_sent = False
                exit_lpr_res  = None
                exit_wait_t   = 0.0
                exit_poll_t   = 0.0
                exit_retry_t  = 0.0
                state = "at_exit_gate"
                print(f"[VC] 상태: {state}", flush=True)

        # ── 결제 완료 폴링 (inside 상태) ─────────────────────────────
        if state == "inside" and not paid:
            park_poll_t += dt
            if park_poll_t >= PARK_POLL_INTERVAL:
                park_poll_t = 0.0
                status = get_pms_status()
                print(f"[VC] PMS 상태: {status}", flush=True)
                if status == "paid":
                    paid = True
                    print("[VC] 결제 완료! 출구로 이동하세요.", flush=True)

    # ════════════════════════════════════════════════════════════════════
    # at_entry_gate — LPR 입차 + 차단기 열림 대기
    # ════════════════════════════════════════════════════════════════════
    elif state == "at_entry_gate":
        stop()
        cur_speed = 0.0

        if not entry_lpr_sent:
            trigger_entry()
            entry_lpr_sent = True
            print("[VC] 입차 차단기 열림 대기…", flush=True)

        entry_wait_t += dt

        if entry_wait_t >= 2.0:
            entry_poll_t += dt
            if entry_poll_t >= BARRIER_POLL_INTERVAL:
                entry_poll_t = 0.0
                if is_barrier_open(ENTRY_BARRIER_PORT):
                    print("[VC] 입차 차단기 열림 → 통과하세요!", flush=True)
                    state = "inside"
                    park_poll_t = 0.0
                    print(f"[VC] 상태: {state}", flush=True)

        if entry_wait_t >= BARRIER_TIMEOUT:
            print("[VC] 차단기 타임아웃 → 강제 통과", flush=True)
            state = "inside"
            park_poll_t = 0.0
            print(f"[VC] 상태: {state}", flush=True)

    # ════════════════════════════════════════════════════════════════════
    # at_exit_gate — LPR 출차 + 차단기 열림 대기
    # ════════════════════════════════════════════════════════════════════
    elif state == "at_exit_gate":
        stop()
        cur_speed = 0.0

        if not exit_lpr_sent:
            exit_lpr_res  = trigger_exit()
            exit_lpr_sent = True

        pms_status = exit_lpr_res.get("status") if exit_lpr_res else None

        if pms_status == "opened":
            exit_wait_t += dt
            exit_poll_t += dt
            if exit_wait_t >= 2.0 and exit_poll_t >= BARRIER_POLL_INTERVAL:
                exit_poll_t = 0.0
                if is_barrier_open(EXIT_BARRIER_PORT):
                    print("[VC] 출차 차단기 열림 → 출차하세요!", flush=True)
                    state = "done"
                    print(f"[VC] 상태: {state}", flush=True)
            if exit_wait_t >= BARRIER_TIMEOUT:
                print("[VC] 출차 차단기 타임아웃 → 강제 출차", flush=True)
                state = "done"
                print(f"[VC] 상태: {state}", flush=True)

        elif pms_status in ("not_paid", "not_found", None):
            exit_retry_t += dt
            if exit_retry_t >= EXIT_RETRY_INTERVAL:
                print("[VC] 미결제 → 재시도", flush=True)
                exit_lpr_res  = trigger_exit()
                exit_lpr_sent = True
                exit_wait_t   = 0.0
                exit_retry_t  = 0.0

    # ════════════════════════════════════════════════════════════════════
    # done
    # ════════════════════════════════════════════════════════════════════
    elif state == "done":
        stop()
