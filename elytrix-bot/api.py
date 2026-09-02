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

    async def _request(self, method: str, path: str, json: Optional[dict] = None) -> dict[str, Any]:
        session = await self._session_get()
        headers = {"X-Api-Key": self.api_key}
        try:
            async with session.request(method, self.base + path, json=json, headers=headers) as resp:
                try:
                    data = await resp.json(content_type=None)
                except ValueError:
                    data = {"ok": False, "error": f"http {resp.status}"}
                if resp.status != 200 or not data.get("ok", False):
                    raise ApiError(str(data.get("error", f"http {resp.status}")))
                return data
        except aiohttp.ClientError as e:
            raise ApiError(f"не могу достучаться до плагина ({e})") from e

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

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
