"""Verify Java-origin HMAC assertions before parsing request JSON."""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import time
from dataclasses import dataclass
from typing import Mapping, Protocol

from fastapi import Request
from pydantic import ValidationError

from .errors import ApiError, INVALID_REQUEST, UNAUTHENTICATED, UNAVAILABLE
from .models import InvocationAssertion


SIGNING_PREFIX = b"YUAN-HMAC-V1\n"
MAX_BODY_BYTES = 65536
MAX_ASSERTION_CHARS = 16384
_KEY_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_B64 = re.compile(r"^[A-Za-z0-9_-]+$")


class NonceStore(Protocol):
    def claim_nonce(self, key_id: str, jti: str, expires_at: int) -> bool: ...


@dataclass(frozen=True)
class VerifiedInvocation:
    assertion: InvocationAssertion
    raw_body: bytes
    key_id: str


def _decode_b64url(value: str) -> bytes:
    if not _B64.fullmatch(value):
        raise UNAUTHENTICATED
    try:
        raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, base64.binascii.Error) as exc:
        raise UNAUTHENTICATED from exc
    if base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii") != value:
        raise UNAUTHENTICATED
    return raw


class HmacVerifier:
    def __init__(self, *, keys: Mapping[str, bytes], nonce_store: NonceStore,
                 issuer: str = "yuan-platform-java", audience: str = "yuan-agent-api-python"):
        self.keys = dict(keys)
        self.nonce_store = nonce_store
        self.issuer = issuer
        self.audience = audience
        if any(not _KEY_ID.fullmatch(k) or not isinstance(v, bytes) or len(v) < 32
               for k, v in self.keys.items()):
            raise ValueError("HMAC keys require a valid key ID and at least 32 secret bytes")

    async def verify(self, request: Request) -> VerifiedInvocation:
        if not self.keys:
            raise UNAVAILABLE
        if request.scope.get("query_string"):
            raise INVALID_REQUEST
        raw_path = request.scope.get("raw_path", b"")
        if not isinstance(raw_path, bytes) or b"%" in raw_path or b"//" in raw_path:
            raise UNAUTHENTICATED
        try:
            path = raw_path.decode("ascii")
        except UnicodeDecodeError as exc:
            raise UNAUTHENTICATED from exc
        try:
            content_length = int(request.headers.get("content-length", "0"))
        except ValueError as exc:
            raise INVALID_REQUEST from exc
        if content_length > MAX_BODY_BYTES or content_length < 0:
            raise INVALID_REQUEST
        chunks: list[bytes] = []
        body_size = 0
        async for chunk in request.stream():
            body_size += len(chunk)
            if body_size > MAX_BODY_BYTES:
                raise INVALID_REQUEST
            chunks.append(chunk)
        raw_body = b"".join(chunks)
        headers = []
        for name in ("x-yuan-key-id", "x-yuan-assertion", "x-yuan-signature"):
            values = request.headers.getlist(name)
            if len(values) != 1:
                raise UNAUTHENTICATED
            headers.append(values[0])
        key_id, assertion_b64, signature_b64 = headers
        if (not _KEY_ID.fullmatch(key_id) or not assertion_b64
                or len(assertion_b64) > MAX_ASSERTION_CHARS or len(signature_b64) > 128):
            raise UNAUTHENTICATED
        key = self.keys.get(key_id)
        if key is None:
            raise UNAUTHENTICATED
        supplied = _decode_b64url(signature_b64)
        expected = hmac.new(key, SIGNING_PREFIX + assertion_b64.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, supplied):
            raise UNAUTHENTICATED
        try:
            assertion = InvocationAssertion.model_validate_json(_decode_b64url(assertion_b64))
        except (ValidationError, ValueError) as exc:
            raise UNAUTHENTICATED from exc
        now = int(time.time())
        if (assertion.iss != self.issuer or assertion.aud != self.audience
                or assertion.method != request.method or assertion.path != path
                or assertion.body_sha256 != hashlib.sha256(raw_body).hexdigest()
                or assertion.iat > now + 5 or assertion.iat < now - 60
                or assertion.exp <= now or assertion.exp <= assertion.iat
                or assertion.exp - assertion.iat > 60
                or assertion.deadline_epoch_ms > assertion.exp * 1000):
            raise UNAUTHENTICATED
        if not assertion.capabilities or len(set(assertion.capabilities)) != len(assertion.capabilities):
            raise UNAUTHENTICATED
        if any(not re.fullmatch(r"[a-z][a-z0-9_.:-]{0,127}", capability)
               for capability in assertion.capabilities):
            raise UNAUTHENTICATED
        grants = [(g.group, g.resource_id, g.version) for g in assertion.grants]
        if len(grants) != len(set(grants)):
            raise UNAUTHENTICATED
        try:
            claimed = self.nonce_store.claim_nonce(key_id, str(assertion.jti), assertion.exp)
        except Exception as exc:
            raise UNAVAILABLE from exc
        if not claimed:
            raise UNAUTHENTICATED
        return VerifiedInvocation(assertion, raw_body, key_id)
