"""Клиент HTTP API плагина ElytrixAuth (бот не ходит в БД напрямую)."""
import logging
from typing import Any, Optional

import aiohttp

log = logging.getLogger("elytrix.api")


class ApiError(Exception):
    pass


class ElytrixApi:
    def __init__(self, base: str, api_key: str) -> None:
        self.base = base.rstrip("/")
        self.api_key = api_key
        self._session: Optional[aiohttp.ClientSession] = None

    async def _session_get(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10))
        return self._session

    async def _call(self, method: str, path: str, json: Optional[dict] = None) -> dict[str, Any]:
        """Низкоуровневый запрос: поднимает ApiError только при сетевых/HTTP ошибках.

        ok:false в теле не считает ошибкой — это бизнес-ответ (неверный код, плохой пароль),
        его разбирают вызывающие методы.
        """
        session = await self._session_get()
        headers = {"X-Api-Key": self.api_key}
        try:
            async with session.request(method, self.base + path, json=json, headers=headers) as resp:
                try:
                    data = await resp.json(content_type=None)
                except ValueError:
                    data = {"ok": False, "error": f"http {resp.status}"}
                if resp.status >= 400:
                    raise ApiError(str(data.get("error", f"http {resp.status}")))
                return data
        except aiohttp.ClientError as e:
            raise ApiError(f"не могу достучаться до плагина ({e})") from e

    async def _request(self, method: str, path: str, json: Optional[dict] = None) -> dict[str, Any]:
        data = await self._call(method, path, json)
        if not data.get("ok", False):
            raise ApiError(str(data.get("error", "unknown error")))
        return data

    async def health(self) -> bool:
        try:
            await self._request("GET", "/api/health")
            return True
        except ApiError as e:
            log.warning("health: %s", e)
            return False

    async def pending(self) -> list[dict[str, Any]]:
        """Список ожидающих подтверждения входов (плагин сам отдаёт только с привязкой TG)."""
        data = await self._request("GET", "/api/pending")
        return list(data.get("requests", []))

    async def resolve(self, request_id: int, action: str) -> bool:
        """action: 'confirm' | 'deny'. True, если запрос был живой и обработан."""
        data = await self._request("POST", "/api/resolve", json={"id": request_id, "action": action})
        return bool(data.get("ok"))

    async def link(self, code: str, tg_id: int) -> Optional[str]:
        """Привязка по коду из /addtg. Возвращает ник игрока или None."""
        data = await self._request("POST", "/api/link", json={"code": code, "tg_id": tg_id})
        return data.get("nickname")

    async def accounts(self, tg_id: int) -> list[dict[str, Any]]:
        """Аккаунты, привязанные к Telegram: [{uuid, nickname, online, tg2fa}, ...]."""
        data = await self._request("GET", f"/api/accounts?tg_id={tg_id}")
        return list(data.get("accounts", []))

    async def kick(self, nickname: str, tg_id: int) -> dict[str, Any]:
        """Кикнуть игрока (если онлайн) + сбросить сессию.

        Возвращает полный ответ: {ok, online} или {ok: false, error}.
        """
        return await self._call("POST", "/api/kick",
                                json={"nickname": nickname, "tg_id": tg_id})

    async def toggle2fa(self, nickname: str, tg_id: int) -> dict[str, Any]:
        """Переключить 2FA аккаунта.

        Возвращает полный ответ: {ok, tg2fa} или {ok: false, error}.
        """
        return await self._call("POST", "/api/toggle2fa",
                                json={"nickname": nickname, "tg_id": tg_id})

    async def change_password(self, nickname: str, tg_id: int, password: str) -> tuple[bool, str]:
        """Полностью сменить пароль аккаунта.

        Возвращает (True, '') при успехе или (False, код_ошибки) — код один из:
        too_short / too_long / like_nick / same_chars / player_not_found / not_yours.
        """
        data = await self._call("POST", "/api/password",
                                json={"nickname": nickname, "tg_id": tg_id, "password": password})
        if data.get("ok"):
            return True, ""
        return False, str(data.get("error", "unknown"))

    async def alerts(self) -> list[dict[str, Any]]:
        """Уведомления о входах (2FA выключена): [{tg_id, text}, ...]. Забирает с сервера."""
        data = await self._request("GET", "/api/alerts")
        return list(data.get("alerts", []))

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
