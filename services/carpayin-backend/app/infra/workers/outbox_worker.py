"""
Outbox 워커.

payment_notification_outbox 테이블의 pending/failed 레코드를
주기적으로 읽어 MQTT로 발행하고 상태를 업데이트한다.

상태 흐름: pending → delivered (성공)
           pending → failed   (재시도 대기, backoff 적용)
           failed  → dead     (max_attempts 초과)
"""

import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.infra.db.models import PaymentNotificationOutbox
from app.infra.db.session import SessionLocal

import threading

_logger = logging.getLogger("carpayin.outbox_worker")

BATCH_SIZE = 20
# attempt 횟수별 백오프(초): 1분 → 2분 → 5분 → 10분 → 30분
_BACKOFF_SECONDS = [60, 120, 300, 600, 1800]


class OutboxWorker:
    def __init__(
        self,
        *,
        notification_publisher,
        interval_seconds: int = 30,
    ) -> None:
        self._publisher = notification_publisher
        self._interval = interval_seconds
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run, daemon=True, name="outbox-worker"
        )
        self._thread.start()
        _logger.info("outbox_worker started (interval=%ds)", self._interval)

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)

    def _run(self) -> None:
        while not self._stop_event.wait(self._interval):
            try:
                self._process_batch()
            except Exception as exc:
                _logger.warning("outbox_worker_scan_error: %s", exc)

    def _process_batch(self) -> None:
        session = SessionLocal()
        try:
            now = datetime.now(timezone.utc)
            records = session.scalars(
                select(PaymentNotificationOutbox)
                .where(
                    PaymentNotificationOutbox.status.in_(["pending", "failed"]),
                    PaymentNotificationOutbox.next_attempt_at <= now,
                    PaymentNotificationOutbox.attempts < PaymentNotificationOutbox.max_attempts,
                )
                .limit(BATCH_SIZE)
            ).all()

            for record in records:
                self._process_record(session, record)
        except Exception as exc:
            _logger.warning("outbox_batch_error: %s", exc)
            session.rollback()
        finally:
            session.close()

    def _process_record(self, session, record: PaymentNotificationOutbox) -> None:
        payload = record.payload or {}
        try:
            self._publisher.publish_payment_notification(
                session_id=str(record.session_id),
                car_id=record.car_id,
                lot_id=payload.get("lot_id", ""),
                tx_id=str(record.tx_id),
                amount=payload.get("amount", 0),
                currency=payload.get("currency", "KRW"),
                approval_no=payload.get("approval_no", ""),
            )
            record.status = "delivered"
            record.attempts = record.attempts + 1
            record.delivered_at = datetime.now(timezone.utc)
            record.failed_reason = None
            session.commit()
            _logger.info("outbox_delivered: tx_id=%s attempt=%d", record.tx_id, record.attempts)
        except Exception as exc:
            session.rollback()
            # 롤백 후 같은 session으로 재조회해서 상태 업데이트
            record = session.get(PaymentNotificationOutbox, record.notification_id)
            if record is None:
                return
            record.attempts = record.attempts + 1
            if record.attempts >= record.max_attempts:
                record.status = "dead"
                record.failed_reason = str(exc)
                _logger.error(
                    "outbox_dead: tx_id=%s after %d attempts, last_error=%s",
                    record.tx_id, record.attempts, exc,
                )
            else:
                record.status = "failed"
                record.failed_reason = str(exc)
                backoff_idx = min(record.attempts - 1, len(_BACKOFF_SECONDS) - 1)
                record.next_attempt_at = datetime.now(timezone.utc) + timedelta(
                    seconds=_BACKOFF_SECONDS[backoff_idx]
                )
                _logger.warning(
                    "outbox_retry_scheduled: tx_id=%s attempt=%d next_at=%s, error=%s",
                    record.tx_id, record.attempts, record.next_attempt_at, exc,
                )
            session.commit()
