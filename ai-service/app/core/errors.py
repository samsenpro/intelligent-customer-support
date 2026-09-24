from enum import StrEnum


class ErrorCode(StrEnum):
    """Códigos de error del servicio. El backend Java decide si reintenta según el status HTTP:
    los 4xx son definitivos y los 5xx transitorios."""

    UNAUTHORIZED = "UNAUTHORIZED"
    INVALID_REQUEST = "INVALID_REQUEST"
    EMBEDDING_FAILED = "EMBEDDING_FAILED"
    VECTOR_STORE_UNAVAILABLE = "VECTOR_STORE_UNAVAILABLE"
    VECTOR_STORE_MISCONFIGURED = "VECTOR_STORE_MISCONFIGURED"
    INTERNAL_ERROR = "INTERNAL_ERROR"

    @property
    def status_code(self) -> int:
        return _STATUS[self]


_STATUS: dict[ErrorCode, int] = {
    ErrorCode.UNAUTHORIZED: 401,
    ErrorCode.INVALID_REQUEST: 422,
    ErrorCode.EMBEDDING_FAILED: 502,
    ErrorCode.VECTOR_STORE_UNAVAILABLE: 503,
    ErrorCode.VECTOR_STORE_MISCONFIGURED: 500,
    ErrorCode.INTERNAL_ERROR: 500,
}


class ServiceError(Exception):
    """Error esperado, con un código estable que se devuelve al cliente."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
