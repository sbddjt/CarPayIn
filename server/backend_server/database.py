import sqlite3
from contextlib import contextmanager

DB_PATH = "backend.db"

def init_db():
    with get_conn() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS vehicles (
                vin               TEXT PRIMARY KEY,
                plate             TEXT,
                cert_hash         TEXT,
                customer_key      TEXT,
                payment_method_id TEXT,
                registered_at     TEXT,
                hyundai_user_id   TEXT,
                hyundai_car_id    TEXT,
                model_name        TEXT,
                year              INTEGER
            );

            -- 현대 OAuth 토큰 저장 (VIN과 별도 관리)
            CREATE TABLE IF NOT EXISTS hyundai_tokens (
                vin                  TEXT PRIMARY KEY,
                hyundai_access_token TEXT,
                hyundai_refresh_token TEXT,
                issued_at            TEXT
            );

            CREATE TABLE IF NOT EXISTS tokens (
                vin           TEXT PRIMARY KEY,
                access_token  TEXT,
                refresh_token TEXT,
                issued_at     TEXT
            );

            CREATE TABLE IF NOT EXISTS pre_notify (
                plate      TEXT,
                lot_id     TEXT,
                vin        TEXT,
                created_at TEXT,
                PRIMARY KEY (plate, lot_id)
            );

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

            CREATE TABLE IF NOT EXISTS transactions (
                tx_id           TEXT PRIMARY KEY,
                session_id      TEXT,
                lot_id          TEXT,
                amount          INTEGER,
                approval_no     TEXT,
                idempotency_key TEXT UNIQUE,
                timestamp       TEXT
            );
        """)

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
