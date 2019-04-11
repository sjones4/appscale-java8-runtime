#!/bin/bash
# Launches the AppScale Java 8 AppServer

J8R_BIN="$(cd -P "$(dirname "$0")" && pwd)"
J8R_HOME="$(dirname "${J8R_BIN}")"
SDK_LIB="${J8R_HOME}/sdk/lib"
SDK_JAR="${SDK_LIB}/appscale-java8-runtime-main.jar"

APP_OPTS="-ea -DAPPLICATION_ID=${APPLICATION_ID}"
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

exec java ${APP_OPTS} -jar "${SDK_JAR}" ${APP_ARGS}