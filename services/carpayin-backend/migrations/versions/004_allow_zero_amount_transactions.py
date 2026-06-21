"""Allow zero-amount transactions for free parking

Revision ID: 004_allow_zero_amount_transactions
Revises: 003_drop_hyundai_tokens
Create Date: 2026-06-18 00:00:00
"""
from alembic import op


revision = "004_allow_zero_amt"
down_revision = "003_drop_hyundai_tokens"
branch_labels = None
depends_on = None


def upgrade():
    op.drop_constraint(
        "ck_transactions_amount",
        "transactions",
        type_="check",
    )
    op.create_check_constraint(
        "ck_transactions_amount",
        "transactions",
        "amount >= 0",
    )


def downgrade():
    op.drop_constraint(
        "ck_transactions_amount",
        "transactions",
        type_="check",
    )
    op.create_check_constraint(
        "ck_transactions_amount",
        "transactions",
        "amount > 0",
    )
