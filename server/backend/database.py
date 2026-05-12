import sqlite3, os
from contextlib import contextmanager

DB_PATH = "backend.db"

def print_schema():
    """서버 시작 시 실제 vehicles 컬럼 목록 출력 (디버그용)"""
    try:
        con = sqlite3.connect(DB_PATH)
        cols = con.execute("PRAGMA table_info(vehicles)").fetchall()
        con.close()
        names = [c[1] for c in cols]
        print(f"[DB] backend.db 경로: {os.path.abspath(DB_PATH)}")
        print(f"[DB] vehicles 컬럼: {names}")
    except Exception as e:
        print(f"[DB] 스키마 확인 실패: {e}")

# vehicles 테이블에 추가될 수 있는 컬럼 목록 (기존 DB 마이그레이션용)
_VEHICLES_COLUMNS = [
    ("customer_key",      "TEXT",    "''"),
    ("payment_method_id", "TEXT",    "''"),
    ("card_last_four",    "TEXT",    "'0000'"),
    ("card_brand",        "TEXT",    "''"),
    ("registered_at",     "TEXT",    "''"),
    ("hyundai_user_id",   "TEXT",    "''"),
    ("hyundai_car_id",    "TEXT",    "''"),
    ("model_name",        "TEXT",    "''"),
    ("year",              "INTEGER", "0"),
]

def _migrate(con):
    """
    기존 DB에 누락된 컬럼을 ALTER TABLE로 추가.
    SQLite는 IF NOT EXISTS를 지원하지 않으므로 PRAGMA로 현재 컬럼 목록을 확인 후 처리.
    """
    # vehicles 테이블 마이그레이션
    existing = {row[1] for row in con.execute("PRAGMA table_info(vehicles)").fetchall()}
    for col_name, col_type, col_default in _VEHICLES_COLUMNS:
        if col_name not in existing:
            con.execute(
                f"ALTER TABLE vehicles ADD COLUMN {col_name} {col_type} DEFAULT {col_default}"
            )
            print(f"[DB 마이그레이션] vehicles.{col_name} 컬럼 추가")

    # login_sessions 테이블 마이그레이션 (vin_hash 추가)
    ls_existing = {row[1] for row in con.execute("PRAGMA table_info(login_sessions)").fetchall()}
    if "vin_hash" not in ls_existing:
        con.execute("ALTER TABLE login_sessions ADD COLUMN vin_hash TEXT DEFAULT ''")
        print("[DB 마이그레이션] login_sessions.vin_hash 컬럼 추가")

def init_db():
    with get_conn() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS vehicles (
                vin               TEXT PRIMARY KEY,
                plate             TEXT,
                customer_key      TEXT    DEFAULT '',
                payment_method_id TEXT    DEFAULT '',
                card_last_four    TEXT    DEFAULT '0000',
                card_brand        TEXT    DEFAULT '',
                registered_at     TEXT    DEFAULT '',
                hyundai_user_id   TEXT    DEFAULT '',
                hyundai_car_id    TEXT    DEFAULT '',
                model_name        TEXT    DEFAULT '',
                year              INTEGER DEFAULT 0
            );

            -- 현대 OAuth 토큰 (VIN과 별도 관리)
            CREATE TABLE IF NOT EXISTS hyundai_tokens (
                vin                   TEXT PRIMARY KEY,
                hyundai_access_token  TEXT,
                hyundai_refresh_token TEXT,
                issued_at             TEXT
            );

            -- CarPayIn 서비스 토큰
            CREATE TABLE IF NOT EXISTS tokens (
                vin           TEXT PRIMARY KEY,
                access_token  TEXT,
                refresh_token TEXT,
                issued_at     TEXT
            );

            -- 지오펜스/내비 기반 입차 사전 알림
            CREATE TABLE IF NOT EXISTS pre_notify (
                plate      TEXT,
                lot_id     TEXT,
                vin        TEXT,
                created_at TEXT,
                PRIMARY KEY (plate, lot_id)
            );

            -- 주차 세션 (입차 확정 ~ 출차 완료)
            CREATE TABLE IF NOT EXISTS sessions (
                session_id  TEXT PRIMARY KEY,
                vin         TEXT,
                plate       TEXT,
                lot_id      TEXT,
                entry_time  TEXT,
                exit_time   TEXT,
                amount      INTEGER DEFAULT 0,
                status      TEXT DEFAULT 'active'
            );

            -- 결제 트랜잭션
            CREATE TABLE IF NOT EXISTS transactions (
                tx_id           TEXT PRIMARY KEY,
                session_id      TEXT,
                lot_id          TEXT,
                amount          INTEGER,
                approval_no     TEXT,
                idempotency_key TEXT UNIQUE,
                timestamp       TEXT
            );

            -- Mock PG 카드 등록 주문 (order_id → VIN 매핑)
            CREATE TABLE IF NOT EXISTS card_orders (
                order_id   TEXT PRIMARY KEY,
                vin        TEXT,
                created_at TEXT
            );

            -- 마