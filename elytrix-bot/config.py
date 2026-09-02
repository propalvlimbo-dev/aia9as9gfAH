"""Конфигурация ElytrixBot из переменных окружения (Pterodactyl) или .env."""
import os
from pathlib import Path

_BASE = Path(__file__).resolve().parent

def _load_dotenv(path: Path = _BASE / ".env") -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip())

_load_dotenv()


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


class Config:
    BOT_TOKEN: str = _env("BOT_TOKEN")

    DB_HOST: str = _env("DB_HOST", "127.0.0.1")
    DB_PORT: int = int(_env("DB_PORT", "3306"))
    DB_NAME: str = _env("DB_NAME", "elytrix")
    DB_USER: str = _env("DB_USER", "elytrix_bot")
    DB_PASSWORD: str = _env("DB_PASSWORD")

    # Как часто бот опрашивает БД на новые запросы входа (сек)
    POLL_INTERVAL: float = float(_env("POLL_INTERVAL", "2.0"))

    @classmethod
    def validate(cls) -> list[str]:
        problems: list[str] = []
        if not cls.BOT_TOKEN:
            problems.append("BOT_TOKEN не задан")
        if not cls.DB_PASSWORD:
            problems.append("DB_PASSWORD не задан")
        return problems
