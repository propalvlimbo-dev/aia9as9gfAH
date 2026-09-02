#!/usr/bin/env bash
# Сборка ElytrixAuth.jar из исходников.
# Нужен JDK 17+ (javac). Готовый jar появится в dist/ElytrixAuth-<version>.jar
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"
VERSION="1.0.0"
DRIVER_JAR="${MARIADB_JAR:-$ROOT/lib/mariadb-java-client.jar}"

if [ ! -f "$DRIVER_JAR" ]; then
    echo "!!! Не найден MariaDB JDBC-драйвер: $DRIVER_JAR"
    echo "    Скачай mariadb-java-client (https://mvnrepository.com/artifact/org.mariadb.jdbc/mariadb-java-client),"
    echo "    положи в elytrixauth-plugin/lib/mariadb-java-client.jar и повтори."
    exit 1
fi

# 1) стабы Bungee API (compile-only) + исходники плагина -> классы
rm -rf build/classes build/jar-merge
mkdir -p build/classes build/jar-merge
# shellcheck disable=SC2046
"$JAVAC" -encoding UTF-8 --release 17 \
    -cp "$ROOT/stubs" \
    -d build/classes \
    $(find src/main/java -name "*.java")

# 2) ресурсы (bungee.yml, config.properties)
cp -r src/main/resources/* build/classes/

# 3) вшиваем MariaDB JDBC-драйвер в jar (shade)
rm -rf /tmp/elytrix-mariadb-extract
mkdir -p /tmp/elytrix-mariadb-extract
( cd /tmp/elytrix-mariadb-extract && "$JAR" --extract --file "$DRIVER_JAR" )
cp -r /tmp/elytrix-mariadb-extract/* build/jar-merge/

# 4) классы плагина + ресурсы
cp -r build/classes/* build/jar-merge/

# 5) упаковка
mkdir -p dist
rm -f "dist/ElytrixAuth-$VERSION.jar"
( cd build/jar-merge && "$JAR" --create --file "$ROOT/dist/ElytrixAuth-$VERSION.jar" . )

echo "OK: $ROOT/dist/ElytrixAuth-$VERSION.jar"
