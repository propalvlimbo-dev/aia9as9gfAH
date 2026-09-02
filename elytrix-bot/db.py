"""Тонкий слой к общей MariaDB (elytrix). Все методы — async, не блокируют цикл."""
import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any, Optional

import pymysql

log = logging.getLogger("elytrix.db")


@dataclass
class LoginRequestRow:
    id: int
    player_uuid: str
    nickname: str
    ip: Optional[str]


@dataclass
class OpenLinkRow:
    id: int
    player_uuid: str
    code: str


class Db:
    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._params = dict(host=host, port=port, user=user, password=password,
                            database=database, charset="utf8mb4",
                            connect_timeout=5, read_timeout=10,
                            cursorclass=pymysql.cursors.DictCursor)
        self._conn: Optional[pymysql.Connection] = None
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------ helpers

    async def _execute(self, sql: str, args: tuple = ()) -> int:
        """UPDATE/INSERT: возвращает rowcount."""
        async with self._lock:
            return await asyncio.to_thread(self._exec_sync, sql, args)

    def _exec_sync(self, sql: str, args: tuple) -> int:
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(sql, args)
            rowcount = cur.rowcount
        conn.commit()
        return rowcount

    async def _fetchone(self, sql: str, args: tuple = ()) -> Optional[dict]:
        async with self._lock:
            return await asyncio.to_thread(self._fetchone_sync, sql, args)

    def _fetchone_sync(self, sql: str, args: tuple) -> Optional[dict]:
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchone()

    async def _fetchall(self, sql: str, args: tuple = ()) -> list[dict]:
        async with self._lock:
            return await asyncio.to_thread(self._fetchall_sync, sql, args)

    def _fetchall_sync(self, sql: str, args: tuple) -> list[dict]:
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return list(cur.fetchall())

    def _connection(self) -> pymysql.Connection:
        if self._conn is None:
            self._conn = pymysql.connect(**self._params)
        try:
            self._conn.ping(reconnect=True)
        except pymysql.MySQLError as e:
            log.warning("reconnect: %s", e)
            self._conn = pymysql.connect(**self._params)
        return self._conn

    # ------------------------------------------------------------------ queries

    async def fetch_pending_logins(self) -> list[LoginRequestRow]:
        now = int(time.time())
        rows = await self._fetchall(
            "SELECT id, player_uuid, nickname, ip FROM login_requests "
            "WHERE status='pending' AND expires_ts > %s ORDER BY id ASC LIMIT 50", (now,))
        return [LoginRequestRow(r["id"], r["player_uuid"], r["nickname"], r["ip"]) for r in rows]

    async def tg_id_by_uuid(self, player_uuid: str) -> Optional[int]:
        row = await self._fetchone("SELECT tg_id FROM players WHERE uuid=%s", (player_uuid,))
        if row and row.get("tg_id") is not None:
            return int(row["tg_id"])
        return None

    async def mark_notified(self, request_id: int) -> None:
        await self._execute("UPDATE login_requests SET status='notified' WHERE id=%s", (request_id,))

    async def settle_login(self, request_id: int, status: str) -> int:
        """confirmed/denied. Возвращает 1, если запрос ещё живой и обновлён."""
        return await self._execute(
            "UPDATE login_requests SET status=%s WHERE id=%s AND status IN ('pending','notified')",
            (status, request_id))

    async def find_open_link(self, code: str) -> Optional[OpenLinkRow]:
        now = int(time.time())
        row = await self._fetchone(
            "SELECT id, player_uuid, code FROM pending_links "
            "WHERE code=%s AND status='open' AND expires_ts > %s ORDER BY id DESC LIMIT 1",
            (code, now))
        if not row:
            return None
        return OpenLinkRow(int(row["id"]), row["player_uuid"], row["code"])

    async def bind_player(self, link_id: int, player_uuid: str, tg_id: int) -> Optional[str]:
        """Привязывает tg к игроку. Возвращает ник игрока или None, если код уже использован."""
        async with self._lock:
            def _bind():
                conn = self._connection()
                with conn.cursor() as cur:
                    cur.execute("UPDATE pending_links SET status='bound' "
                                "WHERE id=%s AND status='open'", (link_id,))
                    if cur.rowcount != 1:
                        return None
                    cur.execute("SELECT nickname FROM players WHERE uuid=%s", (player_uuid,))
                    nick_row = cur.fetchone()
                    if not nick_row:
                        return None
                    cur.execute("UPDATE players SET tg_id=%s WHERE uuid=%s", (tg_id, player_uuid))
                conn.commit()
                return nick_row["nickname"]
            return await asyncio.to_thread(_bind)

    async def unbind_tg(self, tg_id: int) -> int:
        return await self._execute("UPDATE players SET tg_id=NULL WHERE tg_id=%s", (tg_id,))

    async def linked_accounts(self, tg_id: int) -> list[dict]:
        return await self._fetchall(
            "SELECT uuid, nickname FROM players WHERE tg_id=%s ORDER BY nickname", (tg_id,))
