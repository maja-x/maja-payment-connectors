"""MAJA payment client using the Python standard library."""

from __future__ import annotations

import json
import os
import threading
import time
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DEFAULT_TOKEN_URL = (
    "https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token"
)
DEFAULT_BASE_URL = "https://billing.maja.id"
TOKEN_TTL_SECONDS = 300


class MajaError(RuntimeError):
    """Base error raised by the MAJA client."""


class MajaServiceUnavailableError(MajaError):
    """MAJA cannot be reached or returned an invalid server response."""


class MajaBadRequestError(MajaError):
    """MAJA rejected an operation."""


class MajaClient:
    def __init__(
        self,
        *,
        token_url: str | None = None,
        base_url: str | None = None,
        username: str | None = None,
        password: str | None = None,
        client_id: str | None = None,
        client_secret: str | None = None,
        timeout: float = 30,
    ) -> None:
        self.token_url = token_url or os.getenv("MAJA_TOKEN_URL", DEFAULT_TOKEN_URL)
        self.base_url = (base_url or os.getenv("MAJA_BASE_URL", DEFAULT_BASE_URL)).rstrip("/")
        self.username = username if username is not None else os.getenv("MAJA_USERNAME")
        self.password = password if password is not None else os.getenv("MAJA_PASSWORD")
        self.client_id = client_id if client_id is not None else os.getenv("MAJA_CLIENT_ID")
        self.client_secret = client_secret if client_secret is not None else os.getenv("MAJA_CLIENT_SECRET")
        self.timeout = timeout
        self._token: str | None = None
        self._token_at = 0.0
        self._token_lock = threading.Lock()

    def configured(self) -> bool:
        return all((self.username, self.password, self.client_id, self.client_secret))

    def register(self, request: Mapping[str, Any]) -> dict[str, Any]:
        body = self._post_json("/api/v2/register", dict(request))
        if body.get("code") != "00":
            raise MajaBadRequestError(
                f"MAJA register ditolak ({body.get('code', '-')}): {body.get('message', '')}"
            )
        data = body.get("data")
        if not isinstance(data, dict):
            raise MajaServiceUnavailableError("MAJA register tanpa data")
        return data

    def inquiry(self, va: str, invoice_number: str) -> dict[str, Any]:
        body = self._post_json(
            "/api/v2/inquiry", {"va": va, "invoiceNumber": invoice_number}
        )
        data = body.get("data")
        if not isinstance(data, dict):
            raise MajaBadRequestError(
                f"MAJA inquiry gagal ({body.get('code', '-')}): {body.get('message', '')}"
            )
        return data

    def cancel(self, va: str, invoice_number: str) -> None:
        body = self._post_json(
            "/api/v2/cancel", {"va": va, "invoiceNumber": invoice_number}
        )
        if body.get("code") == "00":
            return
        message = str(body.get("message", "")).lower()
        if any(phrase in message for phrase in ("sudah dibatalkan", "invalid", "tidak ditemukan")):
            return
        raise MajaBadRequestError(
            f"MAJA cancel ditolak ({body.get('code', '-')}): {message}"
        )

    def _access_token(self) -> str:
        with self._token_lock:
            if self._token and time.monotonic() - self._token_at < TOKEN_TTL_SECONDS:
                return self._token
            if not self.configured():
                raise MajaServiceUnavailableError(
                    "MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)"
                )

            form = urlencode(
                {
                    "grant_type": "password",
                    "username": self.username,
                    "password": self.password,
                    "client_id": self.client_id,
                    "client_secret": self.client_secret,
                }
            ).encode()
            request = Request(
                self.token_url,
                data=form,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                method="POST",
            )
            status, response_body = self._send(request, "MAJA token")
            if not 200 <= status < 300:
                raise MajaServiceUnavailableError(f"MAJA token gagal (HTTP {status})")
            payload = self._decode_json(response_body, "token")
            token = payload.get("access_token")
            if not isinstance(token, str) or not token:
                raise MajaServiceUnavailableError("Respons MAJA token tanpa access_token")
            self._token = token
            self._token_at = time.monotonic()
            return token

    def _post_json(self, path: str, body: Mapping[str, Any]) -> dict[str, Any]:
        encoded = json.dumps(body, separators=(",", ":")).encode()
        request = Request(
            self.base_url + path,
            data=encoded,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self._access_token()}",
            },
            method="POST",
        )
        status, response_body = self._send(request, path)
        if not 200 <= status < 300:
            message = response_body.decode(errors="replace").strip() or f"HTTP {status}"
            raise MajaServiceUnavailableError(
                f"MAJA {path} gagal (HTTP {status}): {message}"
            )
        return self._decode_json(response_body, path)

    def _send(self, request: Request, operation: str) -> tuple[int, bytes]:
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return response.status, response.read()
        except HTTPError as error:
            return error.code, error.read()
        except (URLError, TimeoutError, OSError) as error:
            raise MajaServiceUnavailableError(
                f"Tidak dapat menghubungi MAJA {operation}: {error}"
            ) from error

    @staticmethod
    def _decode_json(body: bytes, operation: str) -> dict[str, Any]:
        try:
            payload = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise MajaServiceUnavailableError(
                f"Respons MAJA {operation} bukan JSON"
            ) from error
        if not isinstance(payload, dict):
            raise MajaServiceUnavailableError(
                f"Respons MAJA {operation} bukan objek JSON"
            )
        return payload