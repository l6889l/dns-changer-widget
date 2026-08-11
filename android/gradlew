#!/bin/sh

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DIRNAME=`dirname "$0"`

# Resolve links
while [ -h "$DIRNAME/$APP_BASE_NAME" ] ; do
    ls=`ls -ld "$DIRNAME/$APP_BASE_NAME"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        DIRNAME="$link"
    else
        DIRNAME="$DIRNAME/`dirname "$link"`"
    fi
    APP_BASE_NAME=`basename "$link"`
done

# Set APP_HOME
APP_HOME=`cd "$DIRNAME" && pwd`

# Get Java command
if [ -z "$JAVACMD" ] ; then
    if [ -n "$JAVA_HOME" ] ; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        JAVACMD="java"
    fi
fi

if ! command -v "$JAVACMD" > /dev/null 2>&1 ; then
    echo "ERROR: Could not find java. Set JAVA_HOME or add java to PATH." >&2
    exit 1
fi

# Run Gradle wrapper
exec "$JAVACMD" -Xmx2048m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
