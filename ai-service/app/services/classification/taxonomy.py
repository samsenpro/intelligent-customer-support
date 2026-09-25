"""Intenciones, categorías y prioridades que entiende la plataforma (el backend Java usa los mismos
valores para tickets y escalamiento)."""

from enum import StrEnum


class Category(StrEnum):
    GENERAL = "GENERAL"
    BILLING = "BILLING"
    SHIPPING = "SHIPPING"
    PRODUCT = "PRODUCT"
    TECHNICAL = "TECHNICAL"
    ACCOUNT = "ACCOUNT"
    SECURITY = "SECURITY"
    LEGAL = "LEGAL"


class Priority(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    URGENT = "URGENT"

    def raised(self) -> "Priority":
        order = list(Priority)
        return order[min(order.index(self) + 1, len(order) - 1)]


class Intent(StrEnum):
    GREETING = "GREETING"
    GENERAL_QUESTION = "GENERAL_QUESTION"
    PRODUCT_INFO = "PRODUCT_INFO"
    ORDER_STATUS = "ORDER_STATUS"
    SHIPPING_ISSUE = "SHIPPING_ISSUE"
    REFUND_REQUEST = "REFUND_REQUEST"
    BILLING_QUESTION = "BILLING_QUESTION"
    PAYMENT_ISSUE = "PAYMENT_ISSUE"
    CANCELLATION = "CANCELLATION"
    TECHNICAL_ISSUE = "TECHNICAL_ISSUE"
    ACCOUNT_ACCESS = "ACCOUNT_ACCESS"
    FRAUD_REPORT = "FRAUD_REPORT"
    COMPLAINT = "COMPLAINT"
    LEGAL_REQUEST = "LEGAL_REQUEST"
    HUMAN_REQUEST = "HUMAN_REQUEST"


# Categoría y prioridad base de cada intención. La prioridad sube un nivel si el mensaje expresa
# urgencia; el sentimiento nunca cambia la prioridad por sí solo.
INTENT_PROFILE: dict[Intent, tuple[Category, Priority]] = {
    Intent.GREETING: (Category.GENERAL, Priority.LOW),
    Intent.GENERAL_QUESTION: (Category.GENERAL, Priority.LOW),
    Intent.PRODUCT_INFO: (Category.PRODUCT, Priority.LOW),
    Intent.ORDER_STATUS: (Category.SHIPPING, Priority.MEDIUM),
    Intent.SHIPPING_ISSUE: (Category.SHIPPING, Priority.MEDIUM),
    Intent.REFUND_REQUEST: (Category.BILLING, Priority.HIGH),
    Intent.BILLING_QUESTION: (Category.BILLING, Priority.MEDIUM),
    Intent.PAYMENT_ISSUE: (Category.BILLING, Priority.HIGH),
    Intent.CANCELLATION: (Category.BILLING, Priority.MEDIUM),
    Intent.TECHNICAL_ISSUE: (Category.TECHNICAL, Priority.MEDIUM),
    Intent.ACCOUNT_ACCESS: (Category.ACCOUNT, Priority.HIGH),
    Intent.FRAUD_REPORT: (Category.SECURITY, Priority.URGENT),
    Intent.COMPLAINT: (Category.GENERAL, Priority.HIGH),
    Intent.LEGAL_REQUEST: (Category.LEGAL, Priority.HIGH),
    Intent.HUMAN_REQUEST: (Category.GENERAL, Priority.MEDIUM),
}
