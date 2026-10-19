#!/usr/bin/env bash
# build.sh — compila e firma cyberstrike-client.apk senza Gradle.
# Uso:  bash build.sh [android.jar] [build-tools-dir]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

AJ="${1:-${ANDROID_JAR:-}}"
BT="${2:-${BUILD_TOOLS:-}}"
: "${AJ:?serve android.jar (arg1 o ANDROID_JAR)}"
: "${BT:?serve la cartella build-tools (arg2 o BUILD_TOOLS)}"

VERSION_NAME="${VERSION_NAME:-0.1.0}"
# Se VERSION_CODE non è passato, derivalo dalla versione (semver):
#   0.1.24 -> 124, 0.2.0 -> 200, 1.0.0 -> 10000
# Così build locali e release CI restano monotone e confrontabili.
if [ -z "${VERSION_CODE:-}" ]; then
  _ma="${VERSION_NAME%%.*}"; _rest="${VERSION_NAME#*.}"
  _mi="${_rest%%.*}"; _pa="${_rest#*.}"
  _ma="${_ma%%[^0-9]*}"; _mi="${_mi%%[^0-9]*}"; _pa="${_pa%%[^0-9]*}"
  VERSION_CODE=$((10#${_ma:-0} * 10000 + 10#${_mi:-0} * 100 + 10#${_pa:-0}))
fi

# Preferisce il binario dei build-tools, ma solo se è eseguibile su questa
# architettura (i build-tools ufficiali sono x86_64: su arm64 si usano quelli
# di sistema, es. pacchetti Debian/Termux).
pick() {
  local name="$1" cand="$BT/$1"
  if [ -x "$cand" ] && "$cand" --version >/dev/null 2>&1; then
    echo "$cand"
    return
  fi
  command -v "$name" || echo "$cand"
}
AAPT2="$(pick aapt2)"
ZIPALIGN="$(pick zipalign)"
APKSIGNER="$(pick apksigner)"

# D8 dei build-tools 34 va in crash su classi anonime compilate da JDK 21.
# Usa una versione recente di R8 (scaricata una volta in .build/).
R8_VERSION="${R8_VERSION:-8.7.18}"
r8_jar() {
  local jar="$here/.build/r8-$R8_VERSION.jar"
  if [ -f "$jar" ]; then echo "$jar"; return 0; fi
  mkdir -p "$here/.build"
  if command -v curl >/dev/null 2>&1; then
    if curl -fsSL -o "$jar.tmp" \
        "https://maven.google.com/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"; then
      mv "$jar.tmp" "$jar"
      echo "$jar"
      return 0
    fi
    rm -f "$jar.tmp"
  fi
  return 1
}

d8() {
  local jar=""
  if [ -n "${D8_JAR:-}" ] && [ -f "${D8_JAR}" ]; then
    jar="$D8_JAR"
  else
    jar="$(r8_jar || true)"
  fi
  if [ -n "$jar" ] && [ -f "$jar" ]; then
    java -cp "$jar" com.android.tools.r8.D8 "$@"
  elif [ -x "$BT/d8" ]; then
    echo "!! R8 non disponibile: uso d8 dei build-tools (può fallire)" >&2
    "$BT/d8" "$@"
  else
    java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
  fi
}

echo "android.jar : $AJ"
echo "build-tools : $BT"
echo "versione    : $VERSION_NAME ($VERSION_CODE)"

rm -rf gen classes dex res.zip unsigned.apk app.apk aligned.apk cyberstrike-client.apk

echo "-- aapt2 compile"
"$AAPT2" compile --dir res -o res.zip

echo "-- aapt2 link"
"$AAPT2" link -o unsigned.apk -I "$AJ" --manifest AndroidManifest.xml -A assets res.zip \
  --java gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "-- javac"
mkdir -p classes
javac --release 8 -cp "$AJ" -d classes $(find src gen -name '*.java')

echo "-- d8"
mkdir -p dex
d8 --lib "$AJ" --min-api 24 --output dex $(find classes -name '*.class')

cp unsigned.apk app.apk
( cd dex && zip -q ../app.apk classes.dex )

echo "-- zipalign"
"$ZIPALIGN" -f 4 app.apk aligned.apk

KS="${KEYSTORE:-ks.jks}"
if [ ! -f "$KS" ]; then
  echo "-- genero keystore di sviluppo"
  keytool -genkeypair -keystore "$KS" -alias "${KS_ALIAS:-cyberstrike}" \
    -storepass "${KS_PASS:-android}" -keypass "${KEY_PASS:-android}" \
    -dname "CN=CyberStrike,O=CyberStrike,C=IT" -keyalg RSA -keysize 2048 -validity 10000
fi

echo "-- firma"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "${KS_ALIAS:-cyberstrike}" \
  --ks-pass "pass:${KS_PASS:-android}" --key-pass "pass:${KEY_PASS:-android}" \
  --out cyberstrike-client.apk aligned.apk

"$APKSIGNER" verify --print-certs cyberstrike-client.apk | head -3
echo "OK -> $here/cyberstrike-client.apk ($(du -h cyberstrike-client.apk | cut -f1))"
