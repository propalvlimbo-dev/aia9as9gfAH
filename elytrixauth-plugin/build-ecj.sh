#!/usr/bin/env bash
# Сборка ElytrixAuth.jar через ECJ (Eclipse Compiler for Java) + JRE.
# Используется в песочнице без JDK: ECJ работает на любой JRE >= 21.
# HSQLDB (lib/hsqldb.jar) вшивается в jar.
# Результат: dist/ElytrixAuth-<version>.jar
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

JAVA_BIN="${JAVA_BIN:-java}"
ECJ_JAR="${ECJ_JAR:-/tmp/ecm/org/eclipse/jdt/ecj/3.46.0/ecj-3.46.0.jar}"
VERSION="1.0.0"

if [ ! -f "$ECJ_JAR" ]; then
    echo "!!! Нет ECJ: $ECJ_JAR (скачай ecj-3.46.0.jar или укажи ECJ_JAR=...)"
    exit 1
fi
if [ ! -f "$ROOT/lib/hsqldb.jar" ]; then
    echo "!!! Нет $ROOT/lib/hsqldb.jar"
    exit 1
fi

# 1) компиляция (исходники плагина + стабы Bungee API)
rm -rf build/classes build/jar-merge
mkdir -p build/classes build/jar-merge
"$JAVA_BIN" -jar "$ECJ_JAR" \
    -source 17 -target 17 -proc:none -nowarn \
    -encoding UTF-8 \
    -d build/classes \
    $(find src/main/java stubs -name "*.java")

# 2) ресурсы (bungee.yml, config.properties)
cp -r src/main/resources/* build/classes/

# 3) вшиваем HSQLDB (извлечение zip: python, jar == zip)
rm -rf /tmp/elytrix-hsqldb-extract
mkdir -p /tmp/elytrix-hsqldb-extract
python3 - "$ROOT/lib/hsqldb.jar" /tmp/elytrix-hsqldb-extract <<'PYEOF'
import sys, zipfile, pathlib
src, dst = sys.argv[1], pathlib.Path(sys.argv[2])
with zipfile.ZipFile(src) as z:
    for n in z.namelist():
        if n.endswith('/'):
            continue
        p = dst / n
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(z.read(n))
PYEOF
rm -f /tmp/elytrix-hsqldb-extract/module-info.class
cp -r /tmp/elytrix-hsqldb-extract/* build/jar-merge/

# 4) классы плагина + ресурсы
cp -r build/classes/* build/jar-merge/

# 5) упаковка jar (zip через python: jar == zip)
mkdir -p dist
rm -f "dist/ElytrixAuth-$VERSION.jar"
python3 - "dist/ElytrixAuth-$VERSION.jar" build/jar-merge <<'PYEOF'
import sys, zipfile, pathlib
out, src = sys.argv[1], pathlib.Path(sys.argv[2])
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob('*')):
        if p.is_file():
            z.write(p, p.relative_to(src).as_posix())
PYEOF

echo "OK: $ROOT/dist/ElytrixAuth-$VERSION.jar"
