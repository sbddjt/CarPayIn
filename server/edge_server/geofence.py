"""
geofence.py — 제휴 주차장 지오펜스 설정
==========================================
차단기 GPS 실측값:
  Webots 시뮬레이션 좌표: x=53.33, y=3.67
  실제 GPS             : lat=37.493087, lng=127.049750
"""

import math
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ParkingLot:
    id: str
    name: str
    lat: float       # GPS 위도
    lng: float       # GPS 경도
    sim_x: float = 0.0   # Webots 시뮬레이션 X 좌표 (참고용)
    sim_y: float = 0.0   # Webots 시뮬레이션 Y 좌표 (참고용)

    def to_dict(self) -> dict:
        return {
            "lot_id":  self.id,
            "name":    self.name,
            "lat":     self.lat,
            "lng":     self.lng,
        }


# ── 제휴 주차장 목록 (차단기 GPS 실측값 기준) ────────────────────────────────
PARKING_LOTS: list[ParkingLot] = [
    ParkingLot(
        id    = "LOT_TEST_01",
        name  = "테스트 주차장",
        lat   = 37.493087,
        lng   = 127.049750,
        sim_x = 53.33,
        sim_y = 3.67,
    ),
    # TODO: 추가 제휴 주차장 등록
]

# ── 지오펜스 반경 ─────────────────────────────────────────────────────────────
RADIUS_APPROACH_M = 300.0   # 사전 알림 반경: AAOS 앱에 접근 알림 발송
RADIUS_ENTRY_M    = 20.0    # 입차 판정 반경: 차단기 직전 → 자동 입차 트리거


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """두 GPS 좌표 사이의 거리를 미터 단위로 반환합니다."""
    R = 6_371_000  # 지구 반경 (m)
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi      = math.radians(lat2 - lat1)
    d_lambda   = math.radians(lng2 - lng1)
    a = (math.sin(d_phi / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def find_nearest(lat: float, lng: float) -> tuple[Optional[ParkingLot], float]:
    """가장 가까운 제휴 주차장과 거리(m)를 반환합니다."""
    best_lot, best_dist = None, float("inf")
    for lot in PARKING_LOTS:
        d = haversine_m(lat, lng, lot.lat, lot.lng)
        if d < best_dist:
            best_lot, best_dist = lot, d
    return best_lot, best_dist
