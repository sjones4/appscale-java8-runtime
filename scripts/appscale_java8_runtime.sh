#!/bin/bash
# Launches the AppScale Java 8 AppServer

SDK_BIN="$(cd -P "$(dirname "$0")" && pwd)"
SDK_HOME="$(dirname "${SDK_BIN}")"
SDK_LIB="${SDK_HOME}/lib"
CLASSPATH="${SDK_LIB}/appscale-java8-runtime-main.jar:${SDK_LIB}/appengine-tools-api.jar"

APP_OPTS="-DAPPLICATION_ID=${APPLICATION_ID}"
APP_ARGS=""

while test -n "${1}"; do
  case "${1}" in
    --api_using_python_stub=user)
      # we currently use a local service implementation for user
      ;;
    --disable_update_check)
      # ignore, update checks are not supported
      ;;
    --pidfile=*)
      APP_OPTS="${APP_OPTS} -Dappscale.pidfile=${1##--pidfile=}"
      ;;
    --jvm_flag=*)
      APP_OPTS="${APP_OPTS} ${1##--jvm_flag=}"
      ;;
    *)
      APP_ARGS="${APP_ARGS} ${1}"
      ;;
  esac
  shift
done

exec java \
    -ea -cp "${CLASSPATH}" \
    ${APP_OPTS} \
    "com.appscale.appengine.runtime.java8.Main" ${APP_ARGS}

