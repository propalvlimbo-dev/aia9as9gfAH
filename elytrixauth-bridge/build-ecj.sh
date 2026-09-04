#!/usr/bin/env bash
# Сборка ElytrixAuthBridge.jar через ECJ: исходники моста + стабы org.bukkit.
# Результат: dist/ElytrixAuthBridge.jar (ставится на игровой сервер, Bukkit/Paper).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

JAVA_BIN="${JAVA_BIN:-java}"
ECJ_JAR="${ECJ_JAR:-/tmp/ecm/org/eclipse/jdt/ecj/3.46.0/ecj-3.46.0.jar}"

if [ ! -f "$ECJ_JAR" ]; then
    echo "!!! Нет ECJ: $ECJ_JAR"
    exit 1
fi

rm -rf build/classes
mkdir -p build/classes
"$JAVA_BIN" -jar "$ECJ_JAR" \
    -source 17 -target 17 -proc:none -nowarn \
    -encoding UTF-8 \
    -d build/classes \
    $(find src/main/java stubs -name "*.java")

cp -r src/main/resources/* build/classes/

mkdir -p dist
rm -f dist/ElytrixAuthBridge.jar
python3 - "dist/ElytrixAuthBridge.jar" build/classes <<'PYEOF'
import sys, zipfile, pathlib
out, src = sys.argv[1], pathlib.Path(sys.argv[2])
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob('*')):
        if p.is_file():
            z.write(p, p.relative_to(src).as_posix())
PYEOF
echo "OK: $ROOT/dist/ElytrixAuthBridge.jar"
