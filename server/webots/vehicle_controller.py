"""
vehicle_controller.py  –  Webots 자율주행 차량 컨트롤러
==========================================================
역할:
  1. 차량이 자동으로 주차장 방향으로 이동
  2. 주차장 차단기와 가까워지면 (임계 거리 이내) 백엔드에 HTTP POST /pre-notify 전송
  3. 한 번 사전 알림을 보낸 후에는 중복 전송 방지 (쿨다운 적용)
  4. GPS 좌표 계산: Webots 로컬 좌표 → GPS(lat/lng) 변환

주차장 차단기 위치:
  Webots 좌표: x=53.33, y=3.67  (y는 Webots에서 깊이, 즉 z축)
  실제 GPS:    lat=37.493087, lng=127.049750

차량 설정:
  - 백엔드 서버: http://localhost:8002 (Webots는 호스트 PC이므로 localhost 직접 사용)
  - VIN: TESTVIN001 (백엔드 mock에 등록된 테스트 VIN)
  - 번호판: 123가4567 (VIN TESTVIN001에 매핑됨)

사용법:
  Webots world에서 이 파일을 Vehicle 노드의 controller로 지정
  → robot name: "ego_vehicle"
"""

from controller import Robot, GPS
import math
import threading
import time
import json

try:
    import httpx
    _HTTP_LIB = "httpx"
except ImportError:
    import urllib.request
    _HTTP_LIB = "urllib"

# ── 설정 ───────────────────────────────────────────────────────────────────

BACKEND_URL = "http://localhost:8002"

# 차량 정보 (백엔드 mock 데이터와 일치)
VIN            = "TESTVIN001"
PLATE          = "123가4567"
LOT_ID         = "LOT_TEST_01"
ACCESS_TOKEN   = ""   # 등록 후 채워짐

# 주차장 차단기 Webots 좌표 (x, z) — Webots는 y가 높이, z가 깊이
PARKING_LOT_X  = 53.33
PARKING_LOT_Z  = 3.67

# 사전 알림 임계 거리 (Webots 단위 = 미터)
PRE_NOTIFY_THRESHOLD = 15.0   # 15m 이내 → 사전 알림 전송
NOTIFY_COOLDOWN_S    = 30.0   # 한 번 알림 후 30초 쿨다운 (재진입 방지)

# 차량 속도 (m/s)
DEFAULT_SPEED = 5.0

# PMS 주소 (Webots는 호스트 PC에서 실행 → localhost 직접 사용)
PMS_URL = "http://localhost:8001"

# LPR 트리거 임계 거리 (차단기 바로 앞)
LPR_THRESHOLD = 4.0    # 4m 이내 → LPR 카메라 인식 시뮬레이션

# ── 상태 변수 ──────────────────────────────────────────────────────────────
_last_notify_time  = 0.0   # 마지막 사전 알림 전송 시각
_notified_this_lap = False  # 이번 접근에서 사전 알림을 보냈는지
_lpr_triggered     = False  # 이번 접근에서 LPR 트리거를 보냈는지
_lpr_cooldown_s    = 60.0  # LPR 재트리거 쿨다운 (60초)
_last_lpr_time     = 0.0

# ── GPS 좌표 변환 ──────────────────────────────────────────────────────────
# 기준점: Webots (0,0) ↔ GPS (37.493087, 127.049750) 로 설정 (주차장 위치 기준)
# 실제로는 world 파일의 WorldInfo.gpsReference 로 설정하는 게 정확함
REF_LAT   = 37.493087
REF_LNG   = 127.049750
REF_WX    = PARKING_LOT_X   # Webots 원점이 주차장
REF_WZ    = PARKING_LOT_Z

# 위도 1도 ≈ 111,320m / 경도 1도 ≈ 111,320 * cos(lat) m
M_PER_LAT = 111_320.0
M_PER_LNG = 111_320.0 * math.cos(math.radians(REF_LAT))

def webots_to_gps(wx: float, wz: float) -> tuple[float, float]:
    """Webots 로컬 좌표(x, z) → (lat, lng)"""
    dx = wx - REF_WX   # 동서 차이 (x 증가 = 동쪽)
    dz = wz - REF_WZ   # 남북 차이 (z 증가 = 남쪽 — Webots 기준)
    lat = REF_LAT - dz / M_PER_LAT
    lng = REF_LNG + dx / M_PER_LNG
    return lat, lng

# ── HTTP 유틸 ─────────────────────────────────────────────────────────────

def _post_json(url: str, data: dict, timeout: float = 3.0) -> dict | None:
    body = json.dumps(data).encode()
    try:
        if _HTTP_LIB == "httpx":
            res = httpx.post(url, content=body,
                             headers={"Content-Type": "application/json"},
                             timeout=timeout)
            return res.json()
        else:
            req = urllib.request.Request(
                url, data=body,
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
    except Exception as e:
        print(f"[HTTP] POST {url} 실패: {e}")
        return None

def _get_json(url: str, timeout: float = 3.0) -> dict | None:
    try:
        if _HTTP_LIB == "httpx":
            res = httpx.get(url, timeout=timeout)
            return res.json()
        else:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
    except Exception as e:
        print(f"[HTTP] GET {url} 실패: {e}")
        return None

# ── 백엔드 연동 ────────────────────────────────────────────────────────────

def register_with_backend():
    """
    백엔드 헬스 체크.
    차량 등록/인증은 AAOS 앱의 MyHyundai OAuth 흐름에서 처리되므로
    Webots 컨트롤러는 별도 등록 불필요.
    """
    res = _get_json(f"{BACKEND_URL}/")
    if res and res.get("status") == "ok":
        print(f"[백엔드] 연결 확인 — {res.get('service', '')} v{res.get('version', '')}")
        print(f"[백엔드] VIN={VIN} / 번호판={PLATE} — 사전 알림 준비 완료")
    else:
        print("[백엔드] 연결 실패 — 오프라인 모드로 진행 (사전 알림 시 재시도)")

def trigger_lpr():
    """
    차량이 차단기 바로 앞(LPR_THRESHOLD 이내)에 도착 →
    PMS /lpr 엔드포인트 호출 (LPR 카메라 번호판 인식 시뮬레이션)

    PMS 처리:
      1. 모든 차량 → 차단기 즉시 오픈 (클라우드 응답 불필요)
      2. 사전 등록 번호판이면 → 백엔드 /webhook/entry 전송
         → Kafka → 파킹 세션 생성 → MQTT → AAOS 앱 입차 확정 알림
    """
    def _do():
        global _lpr_triggered, _last_lpr_time
        print(f"[LPR] 번호판 인식 시뮬레이션 → PMS POST /lpr  plate={PLATE} lot={LOT_ID}")
        res = _post_json(f"{PMS_URL}/lpr", {
            "plate":  PLATE,
            "lot_id": LOT_ID,
            "gate":   "entry"
        })
        if res:
            barrier = res.get("barrier", "?")
            status  = res.get("status", "?")
            print(f"[LPR] ✓ 응답 → status={status}, barrier={barrier}")
            if barrier == "open":
                print(f"[LPR] ★ 차단기 개방! 입차 진행 중...")
            _last_lpr_time = time.time()
            _lpr_triggered = True
        else:
            print(f"[LPR] ✗ PMS 연결 실패")

    threading.Thread(target=_do, daemon=True).start()


def send_pre_notify():
    """주차장 근접 시 백엔드에 사전 알림 전송 (별도 스레드에서 실행)"""
    def _do():
        global _last_notify_time, _notified_this_lap
        print(f"[사전알림] 백엔드 POST /pre-notify — plate={PLATE}, lot={LOT_ID}")
        res = _post_json(f"{BACKEND_URL}/pre-notify", {
            "vin":     VIN,
            "plate":   PLATE,
            "lot_id":  LOT_ID,
            "trigger": "geofence"
        })
        if res and res.get("status") == "ok":
            _last_notify_time  = time.time()
            _notified_this_lap = True
            print(f"[사전알림] ✓ 완료 → 아이파킹 PMS에 번호판 사전 등록됨")
        else:
            print(f"[사전알림] ✗ 실패 (응답: {res})")

    threading.Thread(target=_do, daemon=True).start()

# ── 차량 구동 ─────────────────────────────────────────────────────────────

def get_distance_to_parking(gps: GPS) -> float:
    """현재 GPS 센서 위치에서 주차장 차단기까지의 Webots 거리(m)"""
    pos = gps.getValues()  # [x, y, z] — y는 높이
    wx, wz = pos[0], pos[2]
    dx = wx - PARKING_LOT_X
    dz = wz - PARKING_LOT_Z
    return math.sqrt(dx * dx + dz * dz)

def steer_toward_parking(robot: Robot, gps: GPS, left_motor, right_motor):
    """
    차량을 주차장 방향으로 조향.
    단순 비례 조향 (P-controller): 방향각 오차에 비례해 좌우 속도 차이를 줌.
    """
    pos = gps.getValues()
    wx, wz = pos[0], pos[2]

    # 주차장 방향 벡터
    dx = PARKING_LOT_X - wx
    dz = PARKING_LOT_Z - wz

    # 차량 전진 방향은 +x (world 기준 초기 설정). 실제 heading은 회전 센서 필요
    # 단순화: z 오차를 조향에 반영
    target_angle = math.atan2(dz, dx)   # [-π, π]

    # 차량이 +x 방향으로 향한다고 가정 (heading = 0)
    # 오차 각도
    heading_err = target_angle   # 초기 heading이 +x방향일 때

    # 조향 비례 계수
    K_STEER = 0.5
    steer   = max(-1.0, min(1.0, K_STEER * heading_err))

    left_speed  = DEFAULT_SPEED * (1.0 - steer)
    right_speed = DEFAULT_SPEED * (1.0 + steer)

    left_motor.setVelocity(left_speed)
    right_motor.setVelocity(right_speed)

# ── 메인 ──────────────────────────────────────────────────────────────────

robot    = Robot()
timestep = int(robot.getBasicTimeStep())

# 센서 및 모터 초기화
gps = robot.getDevice("gps")
gps.enable(timestep)

# 구동 모터 (차종마다 다름 — Webots 기본 car는 아래 이름 사용)
try:
    left_motor  = robot.getDevice("left wheel motor")
    right_motor = robot.getDevice("right wheel motor")
    left_motor.setPosition(float("inf"))
    right_motor.setPosition(float("inf"))
    left_motor.setVelocity(0.0)
    right_motor.setVelocity(0.0)
    HAS_MOTORS = True
except Exception:
    print("[차량] 모터를 찾을 수 없음 — 위치 추적만 동작")
    HAS_MOTORS = False

# 백엔드 등록 (백그라운드)
threading.Thread(target=register_with_backend, daemon=True).start()

print("[차량] 컨트롤러 시작 — 주차장 방향으로 이동 중...")
print(f"  주차장 목표: Webots({PARKING_LOT_X}, {PARKING_LOT_Z})")
print(f"  사전 알림 임계: {PRE_NOTIFY_THRESHOLD}m")

# ── 메인 루프 ──────────────────────────────────────────────────────────────
while robot.step(timestep) != -1:

    dist = get_distance_to_parking(gps)

    # GPS → 실제 좌표 로그 (10초마다)
    pos      = gps.getValues()
    lat, lng = webots_to_gps(pos[0], pos[2])

    if int(robot.getTime()) % 10 == 0:
        print(f"[GPS] Webots({pos[0]:.1f}, {pos[2]:.1f}) → "
              f"GPS({lat:.6f}, {lng:.6f}) | 주차장까지 {dist:.1f}m")

    # ── 단계별 트리거 ─────────────────────────────────────────────────────
    now = time.time()

    # 1) 사전 알림: 15m 이내 최초 진입 시
    pre_cooldown_ok = (now - _last_notify_time) > NOTIFY_COOLDOWN_S
    if dist <= PRE_NOTIFY_THRESHOLD and pre_cooldown_ok and not _notified_this_lap:
        print(f"[차량] ● {dist:.1f}m — 사전 알림 전송 (백엔드 + PMS 번호판 사전 등록)")
        send_pre_notify()

    # 2) LPR 트리거: 4m 이내 최초 진입 시 (차단기 바로 앞)
    lpr_cooldown_ok = (now - _last_lpr_time) > _lpr_cooldown_s
    if dist <= LPR_THRESHOLD and lpr_cooldown_ok and not _lpr_triggered:
        print(f"[차량] ★ {dist:.1f}m — LPR 번호판 인식 트리거 → 차단기 오픈 요청")
        trigger_lpr()

    # 쿨다운 종료 → 플래그 리셋 (재입차 대비)
    if _notified_this_lap and (now - _last_notify_time) > NOTIFY_COOLDOWN_S:
        _notified_this_lap = False
    if _lpr_triggered and (now - _last_lpr_time) > _lpr_cooldown_s:
        _lpr_triggered = False
        print("[차량] LPR 쿨다운 종료 — 다음 입차 대기")

    # ── 주차장 방향으로 이동 ───────────────────────────────────────────────
    if HAS_MOTORS:
        if dist > 2.0:
            steer_toward_parking(robot, gps, left_motor, right_motor)
        else:
            left_motor.setVelocity(0.0)
            right_motor.setVelocity(0.0)
