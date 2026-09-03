package net.md_5.bungee.protocol;

/**
 * Стаб базового пакета BungeeCord. Реальный класс (вместе с реальными
 * пакетами net.md_5.bungee.protocol.packet.*) живёт в прокси, и именно он
 * загружается в рантайме (parent-first). Здесь класс нужен только чтобы
 * скомпилировать вызов unsafe().sendPacket(...).
 */
public abstract class DefinedPacket {
}
