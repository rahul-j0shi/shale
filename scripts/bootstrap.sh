#!/usr/bin/env bash
#
# Installs the project's toolchain *inside the repo* under .tools/ — nothing touches the
# system. Idempotent: safe to re-run. Supports linux/x64, linux/aarch64, macOS/x64 and
# macOS/aarch64 (Apple silicon); anywhere else it says how to install JDK 25 yourself.
#
#   ./scripts/bootstrap.sh          # fetch + verify JDK 25 into .tools/jdk-25
#   source scripts/env.sh           # point this shell at it
#   ./gradlew build                 # first run also fetches Gradle into .tools/gradle-home
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$REPO_ROOT/.tools"
JDK_DIR="$TOOLS/jdk-25"

# Temurin (Eclipse Adoptium) JDK 25 LTS — one pinned release, one pinned checksum per
# platform (N2/N4 spirit: never trust an unverified binary). The checksums are Adoptium's
# published .sha256.txt values for this exact release.
JDK_RELEASE="25.0.3+9"
JDK_BASE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9"

manual_route() {
  cat >&2 <<EOF
shale: $1
Install any JDK 25 yourself, then:
  export JAVA_HOME=<path to that JDK>
  ./gradlew build
(skip this script and scripts/env.sh)
EOF
  exit 1
}

os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64)
    platform="x64_linux"
    JDK_SHA256="69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f" ;;
  Linux/aarch64 | Linux/arm64)
    platform="aarch64_linux"
    JDK_SHA256="3e4287cb98870ba824ed698854bdc27cff984254caf66dd12cc291e7bfdde26b" ;;
  Darwin/x86_64)
    platform="x64_mac"
    JDK_SHA256="4c539a18b4d656960ff6766727e9ca546fc17f7a29714dba9e7b47bdcb37c447" ;;
  Darwin/arm64)
    platform="aarch64_mac"
    JDK_SHA256="7baab4d69a15554e119b86ff78d40e3fdc28819b5b322955c913cebfe3f6a37c" ;;
  *)
    manual_route "no pinned JDK for $os/$arch" ;;
esac
JDK_URL="$JDK_BASE_URL/OpenJDK25U-jdk_${platform}_hotspot_25.0.3_9.tar.gz"

# GNU coreutils has sha256sum; macOS ships shasum instead. Both read "<hash>  <file>".
if command -v sha256sum >/dev/null 2>&1; then
  sha256_check() { sha256sum -c -; }
elif command -v shasum >/dev/null 2>&1; then
  sha256_check() { shasum -a 256 -c -; }
else
  manual_route "neither sha256sum nor shasum is available to verify the download"
fi

mkdir -p "$TOOLS"

if [ -x "$JDK_DIR/bin/java" ]; then
  echo "JDK 25 already installed at $JDK_DIR"
else
  echo "Downloading Temurin JDK $JDK_RELEASE ($platform) ..."
  tmp="$TOOLS/jdk.tar.gz"
  curl -fsSL -o "$tmp" "$JDK_URL"
  echo "Verifying checksum ..."
  echo "$JDK_SHA256  $tmp" | sha256_check
  rm -rf "$TOOLS/jdk-$JDK_RELEASE" "$TOOLS/jdk-25-bundle" "$JDK_DIR"
  tar -xzf "$tmp" -C "$TOOLS"
  rm -f "$tmp"
  if [ -d "$TOOLS/jdk-$JDK_RELEASE/Contents/Home" ]; then
    # A macOS JDK is an app bundle; its JAVA_HOME is Contents/Home. Keep the bundle whole
    # and point jdk-25 at its home, so env.sh needs no per-platform logic.
    mv "$TOOLS/jdk-$JDK_RELEASE" "$TOOLS/jdk-25-bundle"
    ln -s "jdk-25-bundle/Contents/Home" "$JDK_DIR"
  else
    mv "$TOOLS/jdk-$JDK_RELEASE" "$JDK_DIR"
  fi
  echo "Installed JDK 25 to $JDK_DIR"
fi

mkdir -p "$TOOLS/gradle-home"

"$JDK_DIR/bin/java" -version
echo
echo "Bootstrap complete. Next:"
echo "  source scripts/env.sh && ./gradlew build"
