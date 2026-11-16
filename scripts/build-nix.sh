#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

if [[ -z "${SMARTISAN_NIX_BUILD:-}" ]]; then
  exec nix develop "$repo_root" -c env SMARTISAN_NIX_BUILD=1 bash "$0" "$@"
fi

cd "$repo_root"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$repo_root/.android-home}"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

: "${JAVA_HOME:?JAVA_HOME not set; run through nix develop}"
: "${ANDROID_HOME:?ANDROID_HOME not set; run through nix develop}"

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties

cd android
gradle :app:assembleDebug \
  --no-daemon \
  --console=plain \
  --max-workers="${GRADLE_MAX_WORKERS:-2}" \
  -Pkotlin.compiler.execution.strategy=in-process \
  "$@"

ls -l app/build/outputs/apk/debug/app-debug.apk
