#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/dbeaver-mcp.jar"
if [ ! -f "$JAR_PATH" ]; then
    JAR_PATH="$SCRIPT_DIR/target/dbeaver-mcp.jar"
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] Could not find dbeaver-mcp.jar in $SCRIPT_DIR" >&2
    exit 1
fi

JAVA_CMD=""

# 1. Check macOS DBeaver bundle JRE
if [ -d "/Applications/DBeaver.app/Contents/Eclipse/jre/Home/bin" ]; then
    JAVA_CMD="/Applications/DBeaver.app/Contents/Eclipse/jre/Home/bin/java"
elif [ -d "/Applications/DBeaver.app/Contents/jre/bin" ]; then
    JAVA_CMD="/Applications/DBeaver.app/Contents/jre/bin/java"
elif [ -d "/Applications/DBeaver Community.app/Contents/Eclipse/jre/Home/bin" ]; then
    JAVA_CMD="/Applications/DBeaver Community.app/Contents/Eclipse/jre/Home/bin/java"
elif [ -d "/Applications/DBeaver Community.app/Contents/jre/bin" ]; then
    JAVA_CMD="/Applications/DBeaver Community.app/Contents/jre/bin/java"
elif [ -d "$HOME/Applications/DBeaver.app/Contents/Eclipse/jre/Home/bin" ]; then
    JAVA_CMD="$HOME/Applications/DBeaver.app/Contents/Eclipse/jre/Home/bin/java"
elif [ -d "$HOME/Applications/DBeaver.app/Contents/jre/bin" ]; then
    JAVA_CMD="$HOME/Applications/DBeaver.app/Contents/jre/bin/java"
fi

# 2. Check Linux DBeaver JRE (Native, Flatpak, Snap)
if [ -z "$JAVA_CMD" ]; then
    for path in \
        "/usr/share/dbeaver/jre/bin/java" \
        "/usr/lib/dbeaver/jre/bin/java" \
        "/opt/dbeaver/jre/bin/java" \
        "/var/lib/flatpak/app/io.dbeaver.DBeaverCommunity/current/active/files/jre/bin/java" \
        "/snap/dbeaver-ce/current/jre/bin/java"; do
        if [ -x "$path" ]; then
            JAVA_CMD="$path"
            break
        fi
    done
fi

# 3. Check JAVA_HOME
if [ -z "$JAVA_CMD" ] && [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

# 4. Fallback to system java
if [ -z "$JAVA_CMD" ]; then
    JAVA_CMD="java"
fi

exec "$JAVA_CMD" -jar "$JAR_PATH" "$@"
