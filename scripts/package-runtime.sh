#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
MAVEN_BIN="${MAVEN_BIN:-mvn}"
JAVA_HOME_DIR="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
JLINK_BIN="${JLINK_BIN:-${JAVA_HOME_DIR:+$JAVA_HOME_DIR/bin/jlink}}"
if [[ -z "$JLINK_BIN" ]]; then
  JLINK_BIN="$(command -v jlink || true)"
fi

VERSION="$(python3 - <<'PY' "$ROOT_DIR/pom.xml"
import sys
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
print(root.findtext("m:version", namespaces=ns) or "0.0.0")
PY
)"
OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH_NAME="$(uname -m)"
PACKAGE_NAME="fuyue-convert-${VERSION}-${OS_NAME}-${ARCH_NAME}"
PACKAGE_DIR="$DIST_DIR/$PACKAGE_NAME"
RUNTIME_DIR="$PACKAGE_DIR/runtime"

cd "$ROOT_DIR"
MAVEN_ARGS_TEXT="${PACKAGE_MAVEN_ARGS:-${MAVEN_ARGS:-}}"
if [[ -z "$MAVEN_ARGS_TEXT" ]]; then
  MAVEN_ARGS_TEXT="-DskipTests"
elif [[ "$MAVEN_ARGS_TEXT" != *"-DskipTests"* && "$MAVEN_ARGS_TEXT" != *"-Dmaven.test.skip"* ]]; then
  MAVEN_ARGS_TEXT="$MAVEN_ARGS_TEXT -DskipTests"
fi
read -r -a MAVEN_ARGS_ARRAY <<< "$MAVEN_ARGS_TEXT"
"$MAVEN_BIN" "${MAVEN_ARGS_ARRAY[@]}" package

rm -rf "$PACKAGE_DIR"
mkdir -p "$PACKAGE_DIR/app" "$PACKAGE_DIR/bin" "$PACKAGE_DIR/data" "$PACKAGE_DIR/logs"
cp "web-api/target/web-api-$VERSION.jar" "$PACKAGE_DIR/app/fuyue-convert.jar"
cp deploy/application.yml.example "$PACKAGE_DIR/application.yml"
cp README.md README_EN.md LICENSE "$PACKAGE_DIR/"

if [[ -x "$JLINK_BIN" ]]; then
  "$JLINK_BIN" \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported \
    --bind-services \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --output "$RUNTIME_DIR"
else
  echo "未找到 jlink，请设置 JAVA_HOME 或 JLINK_BIN 指向 JDK 17 的 jlink" >&2
  exit 1
fi

cat > "$PACKAGE_DIR/bin/start.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="$APP_HOME/runtime/bin/java"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g -Djava.awt.headless=true}"
URL="http://127.0.0.1:${SERVER_PORT:-8080}"
AUTO_OPEN_BROWSER="${AUTO_OPEN_BROWSER:-true}"

echo "Fuyue Convert 正在启动..."
echo "浏览器地址: $URL"
exec "$JAVA_BIN" $JAVA_OPTS -jar "$APP_HOME/app/fuyue-convert.jar" \
  "--server.port=${SERVER_PORT:-8080}" \
  "--format-converter.auto-open-browser=$AUTO_OPEN_BROWSER" \
  "--spring.config.additional-location=$APP_HOME/application.yml"
SH
chmod +x "$PACKAGE_DIR/bin/start.sh"

cat > "$PACKAGE_DIR/start.command" <<'SH'
#!/usr/bin/env bash
cd "$(dirname "$0")"
./bin/start.sh
SH
chmod +x "$PACKAGE_DIR/start.command"

(
  cd "$DIST_DIR"
  rm -f "$PACKAGE_NAME.tar.gz"
  tar -czf "$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME"
)

echo "已生成 $DIST_DIR/$PACKAGE_NAME.tar.gz"
