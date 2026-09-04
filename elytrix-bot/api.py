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
        """Низкоуровневый запрос: ApiError только при сетевых/HTTP-ошибках.

        ok:false в теле — бизнес-ответ (неверный код, плохой пароль), его
        разбирают вызывающие методы.
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
        """Ожидающие подтверждения входы (только с привязкой TG)."""
        data = await self._request("GET", "/api/pending")
        return list(data.get("requests", []))

    async def resolve(self, request_id: int, action: str) -> bool:
        """action: 'confirm' | 'deny'. True, если запрос был живой и обработан."""
        data = await self._request("POST", "/api/resolve",
                                   json={"id": request_id, "action": action})
        return bool(data.get("ok"))

    async def link(self, code: str, tg_id: int) -> tuple[Optional[str], Optional[str]]:
        """Привязка по коду из /addtg.

        Возвращает (ник, None) при успехе; при ошибке — (None, код_ошибки):
        invalid_or_expired_code / tg_already_linked / ...
        """
        data = await self._call("POST", "/api/link", json={"code": code, "tg_id": tg_id})
        if data.get("ok"):
            return data.get("nickname"), None
        return None, str(data.get("error", "unknown"))

    async def accounts(self, tg_id: int) -> list[dict[str, Any]]:
        """Аккаунты, привязанные к Telegram.

        Каждый: {uuid, nickname, online, tg2fa, frozen, notify}.
        """
        data = await self._request("GET", f"/api/accounts?tg_id={tg_id}")
        return list(data.get("accounts", []))

    async def kick(self, nickname: str, tg_id: int) -> dict[str, Any]:
        """Кикнуть игрока + сбросить сессию. Полный ответ API."""
        return await self._call("POST", "/api/kick",
                                json={"nickname": nickname, "tg_id": tg_id})

    async def toggle2fa(self, nickname: str, tg_id: int) -> dict[str, Any]:
        """Переключить 2FA. Полный ответ API ({ok, tg2fa} или {ok:false,error})."""
        return await self._call("POST", "/api/toggle2fa",
                                json={"nickname": nickname, "tg_id": tg_id})

    async def change_password(self, nickname: str, tg_id: int, password: str) -> dict[str, Any]:
        """Сменить пароль. Полный ответ: {ok:true,kicked:bool} или {ok:false,error}."""
        return await self._call("POST", "/api/password",
                                json={"nickname": nickname, "tg_id": tg_id, "password": password})

    async def freeze(self, nickname: str, tg_id: int, frozen: bool) -> dict[str, Any]:
        """Заморозить/разморозить аккаунт. {ok, frozen, kicked} или {ok:false,error}."""
        return await self._call("POST", "/api/freeze",
                                json={"nickname": nickname, "tg_id": tg_id, "frozen": frozen})

    async def notify(self, nickname: str, tg_id: int, on: bool) -> dict[str, Any]:
        """Вкл/выкл уведомления о входах. {ok, notify} или {ok:false,error}."""
        return await self._call("POST", "/api/notify",
                                json={"nickname": nickname, "tg_id": tg_id, "notify": on})

    async def logins(self, nickname: str, tg_id: int) -> list[dict[str, Any]]:
        """Последние 10 входов: [{ip, ts}, ...] (свежие первыми)."""
        data = await self._request("GET",
                                   f"/api/logins?tg_id={tg_id}&nickname={nickname}")
        return list(data.get("logins", []))

    async def events(self, nickname: str, tg_id: int) -> dict[str, Any]:
        """Активные ивенты (заглушка) + проверка привилегии Elder."""
        data = await self._call("GET",
                                f"/api/events?tg_id={tg_id}&nickname={nickname}")
        if not data.get("ok"):
            raise ApiError(str(data.get("error", "unknown")))
        return data

    async def alerts(self) -> list[dict[str, Any]]:
        """Уведомления о входах: [{tg_id, player_uuid, text}]. Забирает с сервера."""
        data = await self._request("GET", "/api/alerts")
        return list(data.get("alerts", []))

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
