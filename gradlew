#!/bin/sh
# Minimal Gradle launcher wrapper for POSIX systems.

APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "${0%/*}" > /dev/null && pwd -P)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1; then
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
    exit 1
fi

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
