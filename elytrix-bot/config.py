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

    # HTTP API плагина ElytrixAuth (бот НЕ ходит в БД)
    API_BASE: str = _env("API_BASE", "http://127.0.0.1:8754").rstrip("/")
    API_KEY: str = _env("API_KEY")

    # Как часто опрашивать плагин на новые запросы входа (сек)
    POLL_INTERVAL: float = float(_env("POLL_INTERVAL", "2.0"))

    # Канал для раздела «Награды и бонусы»: подписка обязательна.
    # Пусто = проверка выключена. Бот должен быть администратором канала,
    # иначе проверять членство Telegram API не разрешит.
    CHANNEL_ID: str = _env("CHANNEL_ID", "@elytrix_ru")

    @classmethod
    def validate(cls) -> list[str]:
        problems: list[str] = []
        if not cls.BOT_TOKEN:
            problems.append("BOT_TOKEN не задан")
        if not cls.API_KEY:
            problems.append("API_KEY не задан (тот же, что api.secret в config.properties плагина)")
        return problems
