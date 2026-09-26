"""Stable protocol errors without internal exception details."""

from dataclasses import dataclass


@dataclass
class ApiError(Exception):
    status_code: int
    code: str


INVALID_REQUEST = ApiError(400, "INVALID_REQUEST")
UNAUTHENTICATED = ApiError(401, "INVALID_SERVICE_ASSERTION")
FORBIDDEN = ApiError(403, "RESOURCE_DENIED")
NOT_FOUND = ApiError(404, "RUN_NOT_FOUND")
CONFLICT = ApiError(409, "RUN_CONFLICT")
UNSUPPORTED = ApiError(422, "WORKFLOW_UNSUPPORTED")
UNAVAILABLE = ApiError(503, "SERVICE_UNAVAILABLE")
