#!/bin/bash
# NEF Viewer Android 构建入口。
# 依赖下载走 ~/.gradle/gradle.properties 里配的 127.0.0.1:7892 代理（需代理在线）。
cd "$(dirname "$0")"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
if ! nc -z 127.0.0.1 7892 2>/dev/null; then
  echo "警告: 7892 代理不在线，Gradle 依赖下载可能失败" >&2
fi
exec ./gradlew "$@"
