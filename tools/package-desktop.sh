#!/usr/bin/env bash
set -euo pipefail
kind="${1:-}"
case "$(uname -s):$kind" in
  Darwin:pkg) ;;
  Linux:deb|Linux:rpm) ;;
  *) echo 'Build locally on the target OS: macOS pkg; Linux deb or rpm.' >&2; exit 2;;
esac
: "${JAVA_HOME:?Set JAVA_HOME to a local JDK 17 with jpackage}"
: "${GRADLE_BIN:?Set GRADLE_BIN to local Gradle 8.13 executable}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
"$GRADLE_BIN" --no-daemon --console=plain :desktop:test :desktop:packageInput
args=(--type "$kind" --name Uzelio --app-version "${VERSION:-0.7.0}" --vendor Uzelio
  --input "$root/desktop/build/package-input" --main-jar desktop.jar --main-class org.uzel.desktop.DesktopMain
  --dest "$root/dist/desktop/$kind-${VERSION:-0.7.0}" --add-modules java.desktop,java.naming,jdk.crypto.ec --java-options -Dfile.encoding=UTF-8)
if [[ "$kind" == deb || "$kind" == rpm ]]; then args+=(--linux-shortcut --linux-app-category Network); fi
"$JAVA_HOME/bin/jpackage" "${args[@]}"
