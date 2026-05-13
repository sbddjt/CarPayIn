import sqlite3, os
from contextlib import contextmanager
from datetime import datetime, timedelta

DB_PATH = "backend.db"

# 토큰 유효기간 상수
ACCESS_TOKEN_TTL_HOURS   = 24        # 액세스 토큰: 24시간
REFRESH_TOKEN_TTL_DAYS   = 30        # 리프레시 토큰: 30일
LOGIN_PENDING_TTL_MINUTES = 15       # QR 로그인 진행 세션: 15분
LOGIN_FINISHED_TTL_DAYS   = 7        # 완료/실패 로그인 세션 보관: 7일
CARD_ORDER_TTL_MINUTES    = 30       # 카드 등록 주문 대기: 30분

def token_expires_at() -> str:
    """액세스 토큰 만료 시각 (ISO 형식)"""
    return (datetime.now() + timedelta(hours=ACCESS_TOKEN_TTL_HOURS)).isoformat()

def refresh_expires_at() -> str:
    """리프레시 토큰 만료 시각 (ISO 형식)"""
    return (datetime.now() + timedelta(days=REFRESH_TOKEN_TTL_DAYS)).isoformat()

# vehicles 테이블에 필요할 수 있는 컬럼 목록
_VEHICLES_COLUMNS = [
    ("car_id",            "TEXT",    "''"),
    ("customer_key",      "TEXT",    "''"),
    ("payment_method_id", "TEXT",    "''"),
    ("card_last_four",    "TEXT",    "'0000'"),
    ("card_brand",        "TEXT",    "''"),
    ("registered_at",     "TEXT",    "''"),
    ("hyundai_user_id",   "TEXT",    "''"),
    ("hyundai_car_id",    "TEXT",    "''"),
    ("model_name",        "TEXT",    "''"),
    ("year",              "INTEGER", "0"),
    ("vin_hash",          "TEXT",    "''"),
]

def _ensure_schema(con):
    """
    기존 DB에 누락된 컬럼을 ALTER TABLE로 조용히 보정.
    SQLite는 IF NOT EXISTS를 지원하지 않으므로 PRAGMA로 현재 컬럼 목록을 확인 후 처리.
    """
    # vehicles 테이블 컬럼 보정
    existing = {row[1] for row in con.execute("PRAGMA table_info(vehicles)").fetchall()}
    for col_name, col_type, col_default in _VEHICLES_COLUMNS:
        if col_name not in existing:
            con.execute(
                f"ALTER TABLE vehicles ADD COLUMN {col_name} {col_type} DEFAULT {col_default}"
            )

    # login_sessions 테이블 컬럼 보정
    con.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_vehicles_car_id ON vehicles(car_id) WHERE car_id != ''")

    ls_existing = {row[1] for row in con.execute("PRAGMA table_info(login_sessions)").fetchall()}
    if "vin_hash" not in ls_existing:
        con.execute("ALTER TABLE login_sessions ADD COLUMN vin_hash TEXT DEFAULT ''")
    if "debug_message" not in ls_existing:
        con.execute("ALTER TABLE login_sessions ADD COLUMN debug_message TEXT DEFAULT ''")

    # tokens 테이블 컬럼 보정
    if "selected_car_id" not in ls_existing:
        con.execute("ALTER TABLE login_sessions ADD COLUMN selected_car_id TEXT DEFAULT ''")

    tk_existing = {row[1] for row in con.execute("PRAGMA table_info(tokens)").fetchall()}
    if "car_id" not in tk_existing:
        con.execute("ALTER TABLE tokens ADD COLUMN car_id TEXT DEFAULT ''")
    if "hyundai_user_id" not in tk_existing:
        con.execute("ALTER TABLE tokens ADD COLUMN hyundai_user_id TEXT DEFAULT ''")
    if "expires_at" not in tk_existing:
        # 기존 토큰은 지금부터 24시간 유효로 설정
        con.execute("ALTER TABLE tokens ADD COLUMN expires_at TEXT DEFAULT ''")
        con.execute(f"UPDATE tokens SET expires_at='{token_expires_at()}'")
    if "refresh_expires_at" not in tk_existing:
        con.execute("ALTER TABLE tokens ADD COLUMN refresh_expires_at TEXT DEFAULT ''")
        con.execute(f"UPDATE tokens SET refresh_expires_at='{refresh_expires_at()}'")

    con.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tokens_access_token ON tokens(access_token) WHERE access_token != ''")
    con.execute("CREATE INDEX IF NOT EXISTS idx_tokens_refresh_token ON tokens(refresh_token)")

    ht_existing = {row[1] for row in con.execute("PRAGMA table_info(hyundai_tokens)").fetchall()}
    if "car_id" not in ht_existing:
        con.execute("ALTER TABLE hyundai_tokens ADD COLUMN car_id TEXT DEFAULT ''")
    if "hyundai_user_id" not in ht_existing:
        con.execute("ALTER TABLE hyundai_tokens ADD COLUMN hyundai_user_id TEXT DEFAULT ''")
    if "access_expires_at" not in ht_existing:
        con.execute("ALTER TABLE hyundai_tokens ADD COLUMN access_expires_at TEXT DEFAULT ''")
    if "refresh_expires_at" not in ht_existing:
        con.execute("ALTER TABLE hyundai_tokens ADD COLUMN refresh_expires_at TEXT DEFAULT ''")
    con.execute("CREATE INDEX IF NOT EXISTS idx_hyundai_tokens_user ON hyundai_tokens(hyundai_user_id)")

    pn_existing = {row[1] for row in con.execute("PRAGMA table_info(pre_notify)").fetchall()}
    if "car_id" not in pn_existing:
        con.execute("ALTER TABLE pre_notify ADD COLUMN car_id TEXT DEFAULT ''")

    sess_existing = {row[1] for row in con.execute("PRAGMA table_info(sessions)").fetchall()}
    if "car_id" not in sess_existing:
        con.execute("ALTER TABLE sessions ADD COLUMN car_id TEXT DEFAULT ''")

    co_existing = {row[1] for row in con.execute("PRAGMA table_info(card_orders)").fetchall()}
    if "car_id" not in co_existing:
        con.execute("ALTER TABLE card_orders ADD COLUMN car_id TEXT DEFAULT ''")

def cleanup_expired_records(con=None):
    """시간 제한이 지난 일회성/토큰성 데이터를 정리."""
    if con is None:
        with get_conn() as managed:
            return cleanup_expired_records(managed)

    now = datetime.now().isoformat()
    refresh_cutoff = (datetime.now() - timedelta(days=REFRESH_TOKEN_TTL_DAYS)).isoformat()
    pending_cutoff = (datetime.now() - timedelta(minutes=LOGIN_PENDING_TTL_MINUTES)).isoformat()
    finished_cutoff = (datetime.now() - timedelta(days=LOGIN_FINISHED_TTL_DAYS)).isoformat()
    card_order_cutoff = (datetime.now() - timedelta(minutes=CARD_ORDER_TTL_MINUTES)).isoformat()

    removed_tokens = con.execute(
        "DELETE FROM tokens WHERE refresh_expires_at != '' AND refresh_expires_at < ?", (now,)
    ).rowcount
    removed_tokens += con.execute(
        "DELETE FROM tokens WHERE refresh_expires_at = '' AND issued_at != '' AND issued_at < ?", (refresh_cutoff,)
    ).rowcount
    removed_hyundai_tokens = con.execute(
        "DELETE FROM hyundai_tokens WHERE refresh_expires_at != '' AND refresh_expires_at < ?", (now,)
    ).rowcount
    removed_hyundai_tokens += con.execute(
        "DELETE FROM hyundai_tokens WHERE refresh_expires_at = '' AND issued_at != '' AND issued_at < ?", (refresh_cutoff,)
    ).rowcount
    removed_pending_sessions = con.execute("""
        DELETE FROM login_sessions
        WHERE created_at != ''
          AND created_at < ?
          AND status IN ('pending', 'authorized', 'agreement_required')
    """, (pending_cutoff,)).rowcount
    removed_finished_sessions = con.execute("""
        DELETE FROM login_sessions
        WHERE created_at != ''
          AND created_at < ?
          AND status IN ('complete', 'error', 'agreement_error')
    """, (finished_cutoff,)).rowcount
    removed_card_orders = con.execute(
        "DELETE FROM card_orders WHERE created_at != '' AND created_at < ?", (card_order_cutoff,)
    ).rowcount

    total = (
        removed_tokens
        + removed_hyundai_tokens
        + removed_pending_sessions
        + removed_finished_sessions
        + removed_card_orders
    )
    if total > 0:
        print(
            "[DB 정리] "
            f"app_tokens={removed_tokens} "
            f"hyundai_tokens={removed_hyundai_tokens} "
            f"login_pending={removed_pending_sessions} "
            f"login_finished={removed_finished_sessions} "
            f"card_orders={removed_card_orders}"
        )

def init_db():
    with get_conn() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS vehicles (
                car_id            TEXT PRIMARY KEY,
                plate             TEXT,
                customer_key      TEXT    DEFAULT '',
                payment_method_id TEXT    DEFAULT '',
                card_last_four    TEXT    DEFAULT '0000',
                card_brand        TEXT    DEFAULT '',
                registered_at     TEXT    DEFAULT '',
                hyundai_user_id   TEXT    DEFAULT '',
                model_name        TEXT    DEFAULT '',
                year              INTEGER DEFAULT 0,
                vin_hash          TEXT    DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS hyundai_tokens (
                hyundai_user_id       TEXT PRIMARY KEY,
                car_id                TEXT DEFAULT '',
                hyundai_access_token  TEXT,
                hyundai_refresh_token TEXT,
                issued_at             TEXT,
                access_expires_at     TEXT DEFAULT '',
                refresh_expires_at    TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS tokens (
                access_token        TEXT PRIMARY KEY,
                refresh_token       TEXT UNIQUE,
                car_id              TEXT DEFAULT '',
                hyundai_user_id     TEXT DEFAULT '',
                issued_at           TEXT,
                expires_at          TEXT DEFAULT '',
                refresh_expires_at  TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS pre_notify (
                plate      TEXT,
                lot_id     TEXT,
                car_id     TEXT,
                created_at TEXT,
                PRIMARY KEY (plate, lot_id)
            );

            CREATE TABLE IF NOT EXISTS sessions (
                session_id  TEXT PRIMARY KEY,
                car_id      TEXT,
                plate       TEXT,
                lot_id      TEXT,
                entry_time  TEXT,
                exit_time   TEXT,
                amount      INTEGER DEFAULT 0,
                status      TEXT DEFAULT 'active'
            );

            CREATE TABLE IF NOT EXISTS transactions (
                tx_id           TEXT PRIMARY KEY,
                session_id      TEXT,
                lot_id          TEXT,
                amount          INTEGER,
                approval_no     TEXT,
                idempotency_key TEXT UNIQUE,
                timestamp       TEXT
            );

            CREATE TABLE IF NOT EXISTS card_orders (
                order_id   TEXT PRIMARY KEY,
                car_id     TEXT,
                created_at TEXT
            );

            CREATE TABLE IF NOT EXISTS login_sessions (
                session_id    TEXT PRIMARY KEY,
                vin_hash      TEXT DEFAULT '',
                status        TEXT DEFAULT 'pending',
                access_token  TEXT DEFAULT '',
                refresh_token TEXT DEFAULT '',
                plate_number  TEXT DEFAULT '',
                user_id       TEXT DEFAULT '',
                user_name     TEXT DEFAULT '',
                model_name    TEXT DEFAULT '',
                vin_list_json TEXT DEFAULT '[]',
                debug_message TEXT DEFAULT '',
                selected_car_id TEXT DEFAULT '',
                created_at    TEXT
            );
        """)
        _ensure_schema(con)
        cleanup_expired_records(con)

@contextmanager
def get_conn():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()
