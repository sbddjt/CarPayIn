"""
카드 등록 / Billing Key API 테스트
UC-CARD-001: POST /card/order
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.api.deps import get_create_card_order_service
from app.application.card.create_card_order import CreateCardOrderResult


# ──────────────────────────────────────────────
# 테스트 상수
# ──────────────────────────────────────────────

VALID_PLATE = "12가3456"
VALID_BANK_NAME = "신한은행"
VALID_ORDER_ID = "order-abc-001"
VALID_PG_URL = "https://mock-pg.test/card-register?order_id=order-abc-001"


def test_load_test_current_user_uses_car_id(monkeypatch):
    from app.api.deps import get_current_user

    monkeypatch.setenv("LOAD_TEST_MODE", "true")

    assert get_current_user() == {
        "user_id": "load-test-user",
        "car_id": "load-test-vehicle",
    }


# ──────────────────────────────────────────────
# Stub 클래스
# ──────────────────────────────────────────────

class StubCreateCardOrderService:
    def execute(self, command):
        return CreateCardOrderResult(
            order_id=VALID_ORDER_ID,
            pg_url=VALID_PG_URL,
        )


class StubCreateCardOrderServiceThatFailsWithVehicleNotFound:
    """차량 없음 → 404"""
    def execute(self, command):
        raise LookupError("vehicle_not_found")


class StubCreateCardOrderServiceThatFailsWithBusinessError:
    """약관 미동의, 차량번호 형식 오류 등 비즈니스 오류 → 400"""
    def execute(self, command):
        raise ValueError("agree_terms_required")


class StubCreateCardOrderServiceThatFailsWithMolit:
    """MOLIT 검증 실패 → 422"""
    def execute(self, command):
        raise ValueError("molit_verification_failed")


class StubCreateCardOrderServiceThatFailsWithPg:
    """PG URL 생성 실패 → 502"""
    def execute(self, command):
        raise RuntimeError("pg_url_creation_failed")


# ──────────────────────────────────────────────
# Fixtures
# ──────────────────────────────────────────────

@pytest.fixture
def api_client_authenticated():
    from app.api.deps import get_current_user

    def fake_current_user():
        return {"user_id": "user-001", "car_id": "car-001"}

    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderService()
    )
    app.dependency_overrides[get_current_user] = fake_current_user
    
    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


@pytest.fixture
def api_client_with_vehicle_not_found_stub():
    from app.api.deps import get_current_user

    def fake_current_user():
        return {"user_id": "user-001", "car_id": "car-001"}

    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderServiceThatFailsWithVehicleNotFound()
    )
    app.dependency_overrides[get_current_user] = fake_current_user

    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


@pytest.fixture
def api_client_with_business_error_stub():
    from app.api.deps import get_current_user

    def fake_current_user():
        return {"user_id": "user-001", "car_id": "car-001"}

    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderServiceThatFailsWithBusinessError()
    )
    app.dependency_overrides[get_current_user] = fake_current_user

    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


@pytest.fixture
def api_client_with_molit_failing_stub():
    from app.api.deps import get_current_user

    def fake_current_user():
        return {"user_id": "user-001", "car_id": "car-001"}

    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderServiceThatFailsWithMolit()
    )
    app.dependency_overrides[get_current_user] = fake_current_user

    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


@pytest.fixture
def api_client_with_pg_failing_stub():
    from app.api.deps import get_current_user

    def fake_current_user():
        return {"user_id": "user-001", "car_id": "car-001"}

    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderServiceThatFailsWithPg()
    )
    app.dependency_overrides[get_current_user] = fake_current_user

    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


@pytest.fixture
def api_client_unauthenticated():
    original = app.dependency_overrides.copy()
    app.dependency_overrides[get_create_card_order_service] = (
        lambda: StubCreateCardOrderService()
    )

    try:
        with TestClient(app) as client:
            yield client
    finally:
        app.dependency_overrides = original


# ──────────────────────────────────────────────
# 테스트 클래스
# ──────────────────────────────────────────────

class TestCreateCardOrderApi:
    """UC-CARD-001 - POST /card/order"""

    # ── 성공 케이스 ──

    def test_valid_request_returns_200_with_order_id_and_pg_url(
        self,
        api_client_authenticated,
    ):
        """유효한 요청이면 200과 order_id, pg_url을 반환한다."""
        response = api_client_authenticated.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 200
        body = response.json()
        
        assert "order_id" in body
        assert "pg_url" in body
        assert body["order_id"] == VALID_ORDER_ID
        assert body["pg_url"] == VALID_PG_URL

    # ── 인증 케이스 ──

    def test_unauthenticated_request_returns_401(
        self,
        api_client_unauthenticated,
    ):
        """인증 토큰이 없으면 401을 반환한다."""
        response = api_client_unauthenticated.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 401

    # ── Request validation 케이스 ──

    def test_missing_plate_returns_422(self, api_client_authenticated):
        """plate가 누락되면 422를 반환한다."""
        response = api_client_authenticated.post(
            "/card/order",
            json={
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 422

    def test_missing_bank_name_returns_422(self, api_client_authenticated):
        """bank_name이 누락되면 422를 반환한다."""
        response = api_client_authenticated.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "agree_terms": True,
            },
        )

        assert response.status_code == 422

    def test_missing_agree_terms_returns_422(self, api_client_authenticated):
        """agree_terms가 누락되면 422를 반환한다."""
        response = api_client_authenticated.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
            },
        )

        assert response.status_code == 422

    def test_agree_terms_wrong_type_returns_422(self, api_client_authenticated):
        """agree_terms가 boolean이 아니면 422를 반환한다."""
        response = api_client_authenticated.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": "yes",
            },
        )

        assert response.status_code == 422

    # ── 비즈니스 오류 케이스 ──

    def test_service_value_error_returns_400(
        self,
        api_client_with_business_error_stub,
    ):
        """service가 ValueError를 던지면 400을 반환한다."""
        response = api_client_with_business_error_stub.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 400
        assert response.json()["message"] == "agree_terms_required"


    def test_vehicle_not_found_returns_404(
        self,
        api_client_with_vehicle_not_found_stub,
    ):
        """차량이 없으면 404를 반환한다."""
        response = api_client_with_vehicle_not_found_stub.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 404
        assert response.json()["message"] == "vehicle_not_found"

    def test_molit_verification_failure_returns_422(
        self,
        api_client_with_molit_failing_stub,
    ):
        """MOLIT 검증 실패 시 422를 반환한다."""
        response = api_client_with_molit_failing_stub.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 422
        assert response.json()["message"] == "molit_verification_failed"

    def test_pg_url_creation_failure_returns_502(
        self,
        api_client_with_pg_failing_stub,
    ):
        """PG URL 생성 실패 시 502를 반환한다."""
        response = api_client_with_pg_failing_stub.post(
            "/card/order",
            json={
                "plate": VALID_PLATE,
                "bank_name": VALID_BANK_NAME,
                "agree_terms": True,
            },
        )

        assert response.status_code == 502
        assert response.json()["message"] == "pg_url_creation_failed"
