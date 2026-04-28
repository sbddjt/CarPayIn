import sqlite3
from contextlib import contextmanager

DB_PATH = "parking.db"

def init_db():
    with get_conn() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS sessions (
                session_id   TEXT PRIMARY KEY,
                vin          TEXT,
                plate        TEXT,
                lot_id       TEXT,
                entry_time   TEXT NOT NULL,
                exit_time    TEXT,
                amount       INTEGER DEFAULT 0,
                status       TEXT DEFAULT 'active'
            );

            CREATE TABLE IF NOT EXISTS pre_notify (
                plate        TEXT,
                lot_id       TEXT,
                created_at   TEXT,
                PRIMARY KEY (plate, lot_id)
            );

            CREATE TABLE IF NOT EXISTS transactions (
                tx_id        TEXT PRIMARY KEY,
                session_id   TEXT NOT NULL,
                lot_id       TEXT,
                amount       INTEGER,
                approval_no  TEXT,
                idempotency_key TEXT UNIQUE,
                timestamp    TEXT
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
