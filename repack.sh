#!/bin/bash

REPACK_DIR="build/repack"

# Build and copy dependencies
./gradlew assemble copyDependencies

# Build a new appengine-local-runtime jar with
# original and updated content
[ ! -d "${REPACK_DIR}" ] || rm -rf "${REPACK_DIR}"

mkdir -pv "${REPACK_DIR}/updates"

pushd "${REPACK_DIR}/updates"
  jar xf ../../libs/appscale-java8-runtime.jar
popd
pushd "${REPACK_DIR}"
  cp -v ../deps/appengine-local-runtime-*.jar appengine-local-runtime.jar
  jar uvf appengine-local-runtime.jar -C updates/ .
popd

