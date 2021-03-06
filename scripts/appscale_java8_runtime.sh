#!/bin/bash
# Launches the AppScale Java 8 AppServer

J8R_BIN="$(cd -P "$(dirname "$0")" && pwd)"
J8R_HOME="$(dirname "${J8R_BIN}")"
J8R_JAR="${J8R_HOME}/lib/appscale-java8-runtime-main.jar"

APP_OPTS=""
APP_ARGS="--sdk_root=${J8R_HOME}/sdk --runtime=java8"

if [ ! -z "${APPLICATION_ID}" ] ; then
  APP_ARGS="${APP_ARGS} --application=${APPLICATION_ID}"
fi

while test -n "${1}"; do
  case "${1}" in
    --jvm_flag=*)
      APP_OPTS="${APP_OPTS} ${1##--jvm_flag=}"
      ;;
    *)
      APP_ARGS="${APP_ARGS} ${1}"
      ;;
  esac
  shift
done

exec java ${APP_OPTS} -jar "${J8R_JAR}" ${APP_ARGS}