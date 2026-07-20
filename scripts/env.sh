# Source this (do not execute) to point your current shell at the in-repo toolchain:
#
#   source scripts/env.sh
#
# It sets JAVA_HOME to the vendored JDK 25 and keeps Gradle's distribution and dependency
# caches inside the repo (.tools/gradle-home) rather than ~/.gradle, so the whole
# toolchain is self-contained and removable with `rm -rf .tools`.

# Resolve the repo root from this script's own location (works when sourced from anywhere).
_env_src="${BASH_SOURCE[0]:-${(%):-%N}}"
SHALE_REPO_ROOT="$(cd "$(dirname "$_env_src")/.." && pwd)"

export JAVA_HOME="$SHALE_REPO_ROOT/.tools/jdk-25"
export GRADLE_USER_HOME="$SHALE_REPO_ROOT/.tools/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "shale: JDK not found at $JAVA_HOME — run ./scripts/bootstrap.sh first" >&2
else
  echo "shale toolchain active:"
  echo "  JAVA_HOME=$JAVA_HOME"
  echo "  GRADLE_USER_HOME=$GRADLE_USER_HOME"
  "$JAVA_HOME/bin/java" -version
fi
