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
    --api_using_python_stub=user)
      # we currently use a local service implementation for user
      ;;
    --disable_update_check)
      # ignore, update checks are not supported
      ;;
    --jvm_flag=*)
      APP_OPTS="${APP_OPTS} ${1##--jvm_flag=}"
      ;;
    --python_api_server_flag=--external_api_port=*)
      APP_ARGS="${APP_ARGS} --api_port=${1##--python_api_server_flag=--external_api_port=}"
      ;;
    --path_to_python_api_server=*)
    --python_api_server_flag=*)
      # ignore, externally managed api server is required
      ;;
    *)
      APP_ARGS="${APP_ARGS} ${1}"
      ;;
  esac
  shift
done

exec java ${APP_OPTS} -jar "${J8R_JAR}" ${APP_ARGS}