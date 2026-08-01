// flix-spec's own build (implementation plan section 4.1). This repository
// never compiles Flix: tools/project builds against a pinned, checksummed
// flix.jar fetched by tools/oracle/fetch.sh. Gradle is used here, not mill,
// so upstream's build-tool choice never becomes flix-spec's problem.
plugins {
    id("com.diffplug.spotless") version "7.0.4"
}

repositories {
    mavenCentral()
}

spotless {
    scala {
        target("tools/**/src/**/*.scala")
        scalafmt("3.9.4").configFile(".scalafmt.conf")
    }
}
