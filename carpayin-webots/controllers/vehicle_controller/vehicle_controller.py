"""
CarPayIn 차량 컨트롤러 (Webots 내부 실행)

역할: 차량 이동 + 주차장 4m 이내 진입 시 PMS /lpr/entry 트리거
     결제 완료 후 출구 게이트 도착 시 PMS /lpr/exit 트리거

좌표계: Webots Z-up → translation[X, Y, Z] 에서 X,Y가 수평 평면, Z가 고도
  - ToyotaPrius 시작: (70.7132, 1.04608, -0.173432)
  - 주차장 목표:      (53.33, 3.67)
  - 출구 게이트:      (70.7, 1.0)  ← 시작점 근처로 복귀 시 출차 감지

WEBOTS_DRIVE_MODE:
  auto   - 60초에 걸쳐 주차장까지 자동 이동, 입차 후 30초 대기, 출구로 복귀
  manual - 방향키 / WASD 직접 조작, E키로 수동 출차 트리거
"""
import math, json, os, urllib.request
from datetime import datetime, timezone

# ── Webots 컨트롤러 임포트 ──────────────────────────────────────────────
try:
    from vehicle import Driver
    robot = Driver()
    USING_DRIVER = True
except Exception:
    from controller import Robot
    robot = Robot()
    USING_DRIVER = False

from controller import Keyboard

timestep = int(robot.getBasicTimeStep())


def step_robot():
    if USING_DRIVER:
        return robot.step()
    return robot.step(timestep)

# ── .env 로드 ───────────────────────────────────────────────────────────
_here = os.path.dirname(os.path.abspath(__file__))
_env_path = os.path.join(_here, ".env")
if os.path.exists(_env_path):
    with open(_env_path, encoding="utf-8") as _f:
        for _line in _f:
            _line = _line.strip()
            if not _line or _line.startswith("#") or "=" not in _line:
                continue
            _k, _, _v = _line.partition("=")
            _k = _k.strip(); _v = _v.strip().strip('"').strip("'")
            if _k and _k not in os.environ:
                os.environ[_k] = _v

PMS_URL    = os.environ.get("PARKING_PMS_URL", "http://localhost:8001")
PLATE      = os.environ.get("WEBOTS_PLATE", "12가3456")
LOT_ID     = os.environ.get("WEBOTS_LOT_ID", "LOT_TEST_01")
DRIVE_MODE = os.environ.get("WEBOTS_DRIVE_MODE", "auto").lower()

# ── 좌표 상수 ────────────────────────────────────────────────────────────
PARKING_LOT_X = 53.33
PARKING_LOT_Y = 3.67
START_X, START_Y = 70.7, 1.0   # 출구 게이트 = 시작점 근처

ENTRY_TRIGGER_DIST = 4.0    # 입차 트리거 거리 (m)
EXIT_TRIGGER_DIST  = 4.0    # 출차 트리거 거리 (m, 출구 게이트 기준)
AUTO_PARK_HOLD_SEC = 30.0   # auto 모드: 주차 후 출차 전 대기 (초)
AUTO_DURATION      = 60.0   # auto 모드: 주차장까지 이동 시간 (초)
AUTO_RETURN_DURATION = 30.0 # auto 모드: 출구까지 복귀 시간 (초)

# ── HTTP 헬퍼 ────────────────────────────────────────────────────────────
def post_json(url, data):
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    except Exception as e:
        print(f"[VC] POST 실패 {url}: {e}", flush=True)
        return None

def trigger_entry(sim_time_ref):
    entry_time = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    print(f"[LPR] 입차 감지 → PMS /lpr/entry  plate={PLATE}", flush=True)
    res = post_json(f"{PMS_URL}/lpr/entry", {
        "plate": PLATE,
        "lot_id": LOT_ID,
        "entry_time": entry_time,
    })
    print(f"[LPR] 입차 응답: {res}", flush=True)

def trigger_exit():
    exit_time = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    print(f"[LPR] 출차 감지 → PMS /lpr/exit  plate={PLATE}", flush=True)
    res = post_json(f"{PMS_URL}/lpr/exit", {
        "plate": PLATE,
        "lot_id": LOT_ID,
        "exit_time": exit_time,
    })
    print(f"[LPR] 출차 응답: {res}", flush=True)

# ── GPS 센서 초기화 ──────────────────────────────────────────────────────
gps = robot.getDevice("gps")
if gps:
    gps.enable(timestep)

# ── 키보드 초기화 ────────────────────────────────────────────────────────
keyboard = Keyboard()
keyboard.enable(timestep)

# ── Supervisor: 자신의 translation 필드 참조 (auto 모드 이동용) ──────────
translation_field = None
GROUND_Z = -0.173432
try:
    self_node = robot.getSelf()
    translation_field = self_node.getField("translation")
    GROUND_Z = translation_field.getSFVec3f()[2]
    print(f"[VC] 시작 — mode={DRIVE_MODE}, plate={PLATE}, lot={LOT_ID}", flush=True)
except Exception as e:
    print(f"[VC] Supervisor 불가: {e} — mode={DRIVE_MODE}", flush=True)

print(f"[VC] PMS URL: {PMS_URL}", flush=True)
if DRIVE_MODE == "manual":
    print("[VC] 조작: 방향키/WASD 이동,  E = 수동 출차 트리거", flush=True)

# ── 상태 변수 ────────────────────────────────────────────────────────────
# auto 모드 phase: "to_parking" → "parked" → "to_exit" → "exited"
auto_phase  = "to_parking"
auto_t      = 0.0
park_hold_t = 0.0   # 주차 후 대기 타이머

entry_triggered = False
exit_triggered  = False
last_lpr_time   = 0.0

current_speed = 0.0
current_steer = 0.0
MAX_SPEED  = 20.0
MAX_STEER  = 0.4
SPEED_STEP = 2.0
STEER_STEP = 0.5

# ════════════════════════════════════════════════════════════════════════
while step_robot() != -1:
    sim_time = robot.getTime()
    dt = timestep / 1000.0

    # ── 1. 위치 결정 ────────────────────────────────────────────────────
    if DRIVE_MODE == "auto":
        if auto_phase == "to_parking":
            progress = min(auto_t / AUTO_DURATION, 1.0)
            wx = START_X + (PARKING_LOT_X - START_X) * progress
            wy = START_Y + (PARKING_LOT_Y - START_Y) * progress
            if translation_field:
                translation_field.setSFVec3f([wx, wy, GROUND_Z])
            auto_t += dt
            if progress >= 1.0:
                auto_phase = "parked"
                park_hold_t = 0.0
                print("[VC] 주차 완료 — 출차 대기 중", flush=True)

        elif auto_phase == "parked":
            wx, wy = PARKING_LOT_X, PARKING_LOT_Y
            park_hold_t += dt
            if park_hold_t >= AUTO_PARK_HOLD_SEC:
                auto_phase = "to_exit"
                auto_t = 0.0
                print("[VC] 출구로 복귀 시작", flush=True)

        elif auto_phase == "to_exit":
            progress = min(auto_t / AUTO_RETURN_DURATION, 1.0)
            wx = PARKING_LOT_X + (START_X - PARKING_LOT_X) * progress
            wy = PARKING_LOT_Y + (START_Y - PARKING_LOT_Y) * progress
            if translation_field:
                translation_field.setSFVec3f([wx, wy, GROUND_Z])
            auto_t += dt
            if progress >= 1.0:
                auto_phase = "exited"

        else:  # exited — 루프 리셋
            wx, wy = START_X, START_Y
            auto_phase = "to_parking"
            auto_t = 0.0
            entry_triggered = False
            exit_triggered  = False
            print("[VC] 시뮬레이션 루프 재시작", flush=True)

    else:
        # manual 모드: GPS 또는 translation 에서 현재 위치 읽기
        if gps:
            vals = gps.getValues()
            wx, wy = vals[0], vals[1]
        elif translation_field:
            cur = translation_field.getSFVec3f()
            wx, wy = cur[0], cur[1]
        else:
            wx, wy = START_X, START_Y

        # 키보드 입력
        key = keyboard.getKey()

        if key in (Keyboard.UP, ord('W')):
            current_speed = min(current_speed + SPEED_STEP, MAX_SPEED)
        elif key in (Keyboard.DOWN, ord('S')):
            current_speed = max(current_speed - SPEED_STEP, -MAX_SPEED * 0.3)
        else:
            current_speed = 0.0 if abs(current_speed) < 1.0 else current_speed * 0.8

        if key in (Keyboard.LEFT, ord('A')):
            current_steer = max(current_steer - STEER_STEP, -MAX_STEER)
        elif key in (Keyboard.RIGHT, ord('D')):
            current_steer = min(current_steer + STEER_STEP, MAX_STEER)
        else:
            current_steer = 0.0 if abs(current_steer) < 0.01 else current_steer * 0.7

        if USING_DRIVER:
            robot.setCruisingSpeed(current_speed)
            robot.setSteeringAngle(current_steer)

        # E 키: 수동 출차 트리거
        if key == ord('E') and entry_triggered and not exit_triggered:
            exit_triggered = True
            trigger_exit()

    # ── 2. 입차 LPR 트리거 ──────────────────────────────────────────────
    dist_to_entry = math.sqrt((wx - PARKING_LOT_X) ** 2 + (wy - PARKING_LOT_Y) ** 2)

    if (dist_to_entry <= ENTRY_TRIGGER_DIST
            and not entry_triggered
            and (sim_time - last_lpr_time) > 30.0):
        entry_triggered = True
        last_lpr_time = sim_time
        trigger_entry(sim_time)

    # ── 3. 출차 LPR 트리거 (auto 모드: 출구 근접 감지) ──────────────────
    if DRIVE_MODE == "auto" and entry_triggered and not exit_triggered:
        dist_to_exit = math.sqrt((wx - START_X) ** 2 + (wy - START_Y) ** 2)
        if dist_to_exit <= EXIT_TRIGGER_DIST and auto_phase == "to_exit":
            exit_triggered = True
            trigger_exit()
