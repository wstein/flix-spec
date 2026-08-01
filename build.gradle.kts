// flix-spec's own build (implementation plan section 4.1). This repository
// never compiles Flix: tools/project builds against a pinned, checksummed
// flix.jar fetched by tools/oracle/fetch.sh. Gradle is used here, not mill,
// so upstream's build-tool choice never becomes flix-spec's problem.
plugins {
    id("com.diffplug.spotless") version "8.9.0"
}

repositories {
    mavenCentral()
}

spotless {
    scala {
        target("tools/**/src/**/*.scala")
        scalafmt("3.9.4").configFile(".scalafmt.conf")
    }

    // Scripts, schemas and workflows are hand-written; enforce only what can be
    // enforced without reformatting them. Indentation is deliberately not touched
    // -- reindenting shell or YAML mechanically does more harm than good.
    //
    // Markdown is excluded on purpose: two trailing spaces are a hard line break,
    // so blanket trimming silently changes rendering, and LICENSE.md must stay
    // byte-for-byte as published.
    format("misc") {
        target(
            "**/*.py",
            "**/*.sh",
            "**/*.yml",
            "**/*.json",
            "corpus/fetch",
            ".scalafmt.conf",
            "gradle.properties",
        )
        // The globs above are deliberately broad, so everything not tracked by git
        // must be excluded explicitly -- build output, fetched artifacts, release
        // staging, and tool state directories. Formatting a file the repository
        // does not own is always wrong, and it fails the build for the owner.
        targetExclude(
            ".git/**",
            ".gradle/**",
            ".oracle/**",
            ".remember/**",
            "build/**",
            "**/build/**",
            "dist/**",
            "tmp/**",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
