#!/usr/bin/env bash
# Сборка ElytrixAuth.jar из исходников. Нужен JDK 17+ (javac).
# Derby (встроенная БД) уже лежит в lib/ и вшивается в jar.
# Результат: dist/ElytrixAuth.jar
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

# 1) исходники плагина (compile-only стабы Bungee API в stubs/) -> классы
rm -rf build/classes build/jar-merge
mkdir -p build/classes build/jar-merge
# shellcheck disable=SC2046
"$JAVAC" -encoding UTF-8 --release 17 \
    -cp "$ROOT/stubs" \
    -d build/classes \
    $(find src/main/java -name "*.java")

# 2) ресурсы (bungee.yml, config.properties)
cp -r src/main/resources/* build/classes/

# 3) вшиваем HSQLDB (встроенная БД) в jar
if [ ! -f "$ROOT/lib/hsqldb.jar" ]; then
    echo "!!! Нет $ROOT/lib/hsqldb.jar — положи hsqldb.jar в lib/ (см. README)."
    exit 1
fi
rm -rf /tmp/elytrix-hsqldb-extract
mkdir -p /tmp/elytrix-hsqldb-extract
( cd /tmp/elytrix-hsqldb-extract && "$JAR" --extract --file "$ROOT/lib/hsqldb.jar" )
rm -f /tmp/elytrix-hsqldb-extract/module-info.class
cp -r /tmp/elytrix-hsqldb-extract/* build/jar-merge/

# 4) классы плагина + ресурсы
cp -r build/classes/* build/jar-merge/

# 5) упаковка
mkdir -p dist
rm -f "dist/ElytrixAuth.jar"
( cd build/jar-merge && "$JAR" --create --file "$ROOT/dist/ElytrixAuth.jar" . )

echo "OK: $ROOT/dist/ElytrixAuth.jar"
