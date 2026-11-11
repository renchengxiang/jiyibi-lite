#!/bin/bash
# run-parser-tests.sh —— 纯 javac 编译并运行 VoiceParser 断言测试（无 Gradle / JUnit 依赖）
# 用法：bash tools/run-parser-tests.sh
set -e

DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# 允许 JAVA_HOME 覆盖（CI 无默认 javac 时可用）
if [ -n "$JAVA_HOME" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
fi

"$JAVAC" -d "$OUT" \
  "$DIR/java/com/yimu/lite/VoiceParser.java" \
  "$DIR/tools/ParserTests.java"
"$JAVA" -cp "$OUT" ParserTests
