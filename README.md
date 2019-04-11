# AppScale Java 8 runtime
App Engine Java 8 standard runtime for AppScale.

## Build
To build artifacts for this repository:

```
# ./gradlew assemble
```

To build the distributable runtime which includes parts of the google SDK:

```
# ./gradlew runtime
```

The resulting artifact is numbered as per the source SDK, e.g. `appscale-java8-runtime-1.9.61.zip`