#!/usr/bin/env bash
#
# Installs the project's toolchain *inside the repo* under .tools/ — nothing touches the
# system. Idempotent: safe to re-run. Currently supports linux/x64 (the dev target);
# add a branch here for another platform when needed.
#
#   ./scripts/bootstrap.sh          # fetch + verify JDK 25 into .tools/jdk-25
#   source scripts/env.sh           # point this shell at it
#   ./gradlew build                 # first run also fetches Gradle into .tools/gradle-home
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$REPO_ROOT/.tools"
JDK_DIR="$TOOLS/jdk-25"

# Temurin (Eclipse Adoptium) JDK 25 LTS — pinned release + checksum (N2/N4 spirit:
# never trust an unverified binary).
JDK_RELEASE="25.0.3+9"
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"
JDK_SHA256="69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f"

mkdir -p "$TOOLS"

if [ -x "$JDK_DIR/bin/java" ]; then
  echo "JDK 25 already installed at $JDK_DIR"
else
  echo "Downloading Temurin JDK $JDK_RELEASE (linux/x64) ..."
  tmp="$TOOLS/jdk.tar.gz"
  curl -fsSL -o "$tmp" "$JDK_URL"
  echo "Verifying checksum ..."
  echo "$JDK_SHA256  $tmp" | sha256sum -c -
  tar -xzf "$tmp" -C "$TOOLS"
  rm -f "$tmp"
  rm -rf "$JDK_DIR"
  mv "$TOOLS/jdk-$JDK_RELEASE" "$JDK_DIR"
  echo "Installed JDK 25 to $JDK_DIR"
fi

mkdir -p "$TOOLS/gradle-home"

"$JDK_DIR/bin/java" -version
echo
echo "Bootstrap complete. Next:"
echo "  source scripts/env.sh && ./gradlew build"
