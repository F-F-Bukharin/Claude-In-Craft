#!/bin/sh
# Starts the Claude Code helper on http://127.0.0.1:8787.
# Options pass through, for example:  ./start-helper.sh --token my-secret --model claude-haiku-5-5
# Press Ctrl+C to stop it.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA=java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"; fi
if ! "$JAVA" -version >/dev/null 2>&1; then
  echo "Java was not found. Install Java 17 or newer, for example from https://adoptium.net" >&2
  exit 1
fi
exec "$JAVA" -jar "$DIR/claude-code-helper.jar" "$@"
