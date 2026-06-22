import os

from fastapi import Depends, Header
from sqlalchemy.orm import Session

from app.application.auth.confirm_vehicle import ConfirmVehicleService
from app.application.auth.create_qr_session import CreateQrSessionService
from app.application.auth.get_login_session_status import GetLoginSessionStatusService
from app.application.auth.handle_hyundai_oauth_callback import (
    HandleHyundaiOAuthCallbackService,
)
from app.application.auth.refresh_access_token import RefreshAccessTokenService
from app.application.auth.start_hyundai_oauth import StartHyundaiOAuthService
from app.application.card.create_card_order import CreateCardOrderService
from app.application.card.handle_card_webhook import HandleCardWebhookService
from app.application.parking.handle_entry_webhook import HandleEntryWebhookService
from app.application.parking.register_pre_notify import RegisterPreNotifyService
from app.application.payment.get_parking_fee import GetParkingFeeService
from app.application.payment.process_payment import ProcessPaymentService
from app.infra.clients.hyundai_oauth_client import HttpxHyundaiOAuthClient
from app.infra.clients.molit_client import HttpxMolitClient, LocalMolitBypassClient
from app.infra.clients.pg_client import HttpxPgClient
from app.infra.clients.pms_client import HttpxPmsClient
from app.infra.db.session import get_db_session
from app.infra.redis import (
    RedisAppLoginResultStore,
    RedisCardOrderStore,
    RedisEntryNotifyRetryStore,
    RedisFeeQuoteStore,
    RedisHyundaiOAuthResultStore,
    RedisOAuthStateStore,
    RedisPaymentNotifyRetryStore,
    RedisPreNotifyStore,
    RedisQrSessionStore,
    redis_client,
)
from app.infra.repositories.app_refresh_token_repository import (
    SqlAlchemyAppRefreshTokenRepository,
)
from app.infra.repositories.billing_key_repository import (
    SqlAlchemyBillingKeyRepository,
)
from app.infra.repositories.parking_session_repository import (
    SqlAlchemyParkingSessionRepository,
)
from app.infra.repositories.transaction_repository import (
    SqlAlchemyTransactionRepository,
)
from app.infra.repositories.user_repository import SqlAlchemyUserRepository
from app.infra.repositories.vehicle_repository import SqlAlchemyVehicleRepository
from app.api.utils import extract_bearer_token
from app.infra.security import create_default_security_components
from app.infra.support import (
    PlateNormalizer,
    UuidOrderIdGenerator,
    build_notification_publisher,
)


def _required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if (
        not value
        or value.startswith("your-")
        or value == "hyundai-client-001"
        or "hyundai-dev" in value
    ):
        raise RuntimeError(f"{name} environment variable is required")
    return value


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _requires_explicit_env() -> bool:
    return os.getenv("APP_ENV", "local").strip().lower() in {
        "aws",
        "staging",
        "prod",
        "production",
    }


def _env_or_default(name: str, default: str) -> str:
    value = os.getenv(name, "").strip()
    if value:
        return value
    if _requires_explicit_env():
        raise RuntimeError(f"{name} environment variable is required")
    return default


def _env_or_legacy_default(name: str, legacy_name: str, default: str) -> str:
    value = os.getenv(name, "").strip()
    if value:
        return value
    legacy_value = os.getenv(legacy_name, "").strip()
    if legacy_value:
        return legacy_value
    if _requires_explicit_env():
        raise RuntimeError(f"{name} environment variable is required")
    return default


PUBLIC_BASE_URL = _required_env("PUBLIC_BASE_URL").rstrip("/")
HYUNDAI_AUTHORIZE_URL = os.getenv(
    "HYUNDAI_AUTHORIZE_URL",
    "https://prd.kr-ccapi.hyundai.com/api/v1/user/oauth2/authorize",
)
HYUNDAI_CLIENT_ID = _required_env("HYUNDAI_CLIENT_ID")
HYUNDAI_CLIENT_SECRET = _required_env("HYUNDAI_CLIENT_SECRET")
PG_BASE_URL = _env_or_default("PG_BASE_URL", "http://localhost:8002").rstrip("/")
PG_INTERNAL_BASE_URL = _env_or_default("PG_INTERNAL_BASE_URL", PG_BASE_URL).rstrip("/")
PG_PUBLIC_BASE_URL = _env_or_default("PG_PUBLIC_BASE_URL", PG_BASE_URL).rstrip("/")
CARD_WEBHOOK_URL = _env_or_default(
    "CARD_WEBHOOK_URL",
    f"{PUBLIC_BASE_URL}/card/webhook",
).rstrip("/")
PMS_BASE_URL = _env_or_default("PMS_BASE_URL", "http://localhost:8001").rstrip("/")
MOLIT_VERIFY_ENABLED = _env_bool("MOLIT_VERIFY_ENABLED", True)


qr_session_store = RedisQrSessionStore(redis_client)
oauth_state_store = RedisOAuthStateStore(redis_client)
hyundai_oauth_result_store = RedisHyundaiOAuthResultStore(redis_client)
app_login_result_store = RedisAppLoginResultStore(redis_client)
card_order_store = RedisCardOrderStore(redis_client)
pre_notify_store = RedisPreNotifyStore(redis_client)
fee_quote_store = RedisFeeQuoteStore(redis_client)
entry_notify_retry_store = RedisEntryNotifyRetryStore(redis_client)
payment_notify_retry_store = RedisPaymentNotifyRetryStore(redis_client)
security_components = create_default_security_components()
hyundai_oauth_client = HttpxHyundaiOAuthClient(
    token_url=os.getenv(
        "HYUNDAI_TOKEN_URL",
        "https://prd.kr-ccapi.hyundai.com/api/v1/user/oauth2/token",
    ),
    user_info_url=os.getenv(
        "HYUNDAI_USER_INFO_URL",
        "https://prd.kr-ccapi.hyundai.com/api/v1/user/profile",
    ),
    vehicle_list_url=os.getenv(
        "HYUNDAI_VEHICLE_LIST_URL",
        "https://dev.kr-ccapi.hyundai.com/api/v1/car/profile/carlist",
    ),
    client_id=HYUNDAI_CLIENT_ID,
    client_secret=HYUNDAI_CLIENT_SECRET,
    redirect_uri=f"{PUBLIC_BASE_URL}/auth/redirect",
)
molit_client = (
    HttpxMolitClient(
        base_url=_env_or_default("MOLIT_BASE_URL", "https://molit.test"),
        api_key=_env_or_default("MOLIT_API_KEY", "molit-dev-key"),
    )
    if MOLIT_VERIFY_ENABLED
    else LocalMolitBypassClient()
)
pg_client = HttpxPgClient(
    PG_INTERNAL_BASE_URL,
    public_base_url=PG_PUBLIC_BASE_URL,
    card_webhook_url=CARD_WEBHOOK_URL,
)
pms_client = HttpxPmsClient(
    PMS_BASE_URL,
    webhook_secret=_env_or_legacy_default(
        "PMS_WEBHOOK_SECRET",
        "PMS_WEBHOOK_TOKEN",
        "pms-webhook-secret",
    ),
)
order_id_generator = UuidOrderIdGenerator()
plate_normalizer = PlateNormalizer()
notification_publisher = build_notification_publisher()


def get_current_user(
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> dict:
    
    # 부하테스트용 bypass - 테스트 완료 후 반드시 제거
    if os.getenv("LOAD_TEST_MODE", "").strip().lower() == "true":
        return {"user_id": "load-test-user", "car_id": "load-test-vehicle"}
    #기존 코드
    token = extract_bearer_token(authorization)
    return security_components["app_access_token_validator"].validate_and_extract(token)


def get_create_qr_session_service() -> CreateQrSessionService:
    return CreateQrSessionService(
        qr_session_store=qr_session_store,
        public_base_url=PUBLIC_BASE_URL,
    )


def get_start_hyundai_oauth_service() -> StartHyundaiOAuthService:
    return StartHyundaiOAuthService(
        qr_session_store=qr_session_store,
        oauth_state_store=oauth_state_store,
        public_base_url=PUBLIC_BASE_URL,
        hyundai_authorize_url=HYUNDAI_AUTHORIZE_URL,
        hyundai_client_id=HYUNDAI_CLIENT_ID,
    )


def get_handle_hyundai_oauth_callback_service(
    session: Session = Depends(get_db_session),
) -> HandleHyundaiOAuthCallbackService:
    return HandleHyundaiOAuthCallbackService(
        oauth_state_store=oauth_state_store,
        qr_session_store=qr_session_store,
        hyundai_oauth_client=hyundai_oauth_client,
        user_repository=SqlAlchemyUserRepository(session),
        hyundai_oauth_result_store=hyundai_oauth_result_store,
        app_login_result_store=app_login_result_store,
        temp_access_token_issuer=security_components["temp_access_token_issuer"],
        public_base_url=PUBLIC_BASE_URL,
    )


def get_login_session_status_service() -> GetLoginSessionStatusService:
    return GetLoginSessionStatusService(
        app_login_result_store=app_login_result_store,
        qr_session_store=qr_session_store,
    )


def get_confirm_vehicle_service(
    session: Session = Depends(get_db_session),
) -> ConfirmVehicleService:
    return ConfirmVehicleService(
        temp_access_token_validator=security_components["temp_access_token_validator"],
        hyundai_oauth_result_store=hyundai_oauth_result_store,
        app_login_result_store=app_login_result_store,
        qr_session_store=qr_session_store,
        vehicle_repository=SqlAlchemyVehicleRepository(session),
        app_refresh_token_repository=SqlAlchemyAppRefreshTokenRepository(session),
        app_token_issuer=security_components["app_token_issuer"],
        refresh_token_hasher=security_components["refresh_token_hasher"],
    )


def get_refresh_access_token_service(
    session: Session = Depends(get_db_session),
) -> RefreshAccessTokenService:
    return RefreshAccessTokenService(
        app_refresh_token_repository=SqlAlchemyAppRefreshTokenRepository(session),
        refresh_token_hasher=security_components["refresh_token_hasher"],
        app_access_token_issuer=security_components["app_access_token_issuer"],
    )


def get_create_card_order_service(
    session: Session = Depends(get_db_session),
) -> CreateCardOrderService:
    return CreateCardOrderService(
        vehicle_repository=SqlAlchemyVehicleRepository(session),
        molit_client=molit_client,
        card_order_store=card_order_store,
        pg_client=pg_client,
        order_id_generator=order_id_generator,
    )


def get_handle_card_webhook_service(
    session: Session = Depends(get_db_session),
) -> HandleCardWebhookService:
    return HandleCardWebhookService(
        card_order_store=card_order_store,
        billing_key_repository=SqlAlchemyBillingKeyRepository(session),
        vehicle_repository=SqlAlchemyVehicleRepository(session),
        signature_verifier=security_components["card_webhook_signature_verifier"],
    )


def get_register_pre_notify_service(
    session: Session = Depends(get_db_session),
) -> RegisterPreNotifyService:
    return RegisterPreNotifyService(
        token_validator=security_components["app_access_token_validator"],
        vehicle_repository=SqlAlchemyVehicleRepository(session),
        billing_key_repository=SqlAlchemyBillingKeyRepository(session),
        pre_notify_store=pre_notify_store,
        pms_client=pms_client,
        plate_normalizer=plate_normalizer,
    )


def get_handle_entry_webhook_service(
    session: Session = Depends(get_db_session),
) -> HandleEntryWebhookService:
    return HandleEntryWebhookService(
        pms_auth_validator=security_components["pms_auth_validator"],
        pre_notify_store=pre_notify_store,
        parking_session_repository=SqlAlchemyParkingSessionRepository(session),
        notification_publisher=notification_publisher,
        entry_notify_retry_store=entry_notify_retry_store,
    )


def get_parking_fee_service(
    session: Session = Depends(get_db_session),
) -> GetParkingFeeService:
    return GetParkingFeeService(
        token_validator=security_components["app_access_token_validator"],
        parking_session_repository=SqlAlchemyParkingSessionRepository(session),
        fee_quote_store=fee_quote_store,
        pms_client=pms_client,
    )


def get_process_payment_service(
    session: Session = Depends(get_db_session),
) -> ProcessPaymentService:
    return ProcessPaymentService(
        token_validator=security_components["app_access_token_validator"],
        fee_quote_store=fee_quote_store,
        parking_session_repository=SqlAlchemyParkingSessionRepository(session),
        billing_key_repository=SqlAlchemyBillingKeyRepository(session),
        transaction_repository=SqlAlchemyTransactionRepository(session),
        pg_client=pg_client,
        pms_client=pms_client,
        notification_publisher=notification_publisher,
        payment_notify_retry_store=payment_notify_retry_store,
    )
