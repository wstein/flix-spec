plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version.toString() +
    if (providers.gradleProperty("flixSpec.snapshot").orNull == "true") "-SNAPSHOT" else ""

base { archivesName.set("flix-spec-gradle-plugin") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }
dependencies { testImplementation("junit:junit:4.13.2") }

gradlePlugin {
    plugins {
        create("flixSpec") {
            id = "io.github.wstein.flix-spec"
            implementationClass = "flix.spec.gradle.FlixSpecPlugin"
            displayName = "Flix parser conformance"
            description = "Resolves independently pinned specification and runner artifacts and checks a consumer adapter."
        }
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "flix-spec-gradle-plugin"
    }
    repositories {
        maven {
            name = "flixSpecRepo"
            url = uri(providers.gradleProperty("flixSpec.publishRepo").orElse(
                rootProject.layout.buildDirectory.dir("repo").map { it.asFile.toURI().toString() }
            ).get())
        }
    }
}

// Functional tests use the real executable and bundle, not mocks of the runner's exit behavior.
tasks.test {
    dependsOn(":tools:conformance:runnerJar", ":packaging:artifactsJar")
    inputs.file(rootProject.file("tools/conformance/build/libs/flix-spec-runner-$version.jar"))
    inputs.file(rootProject.file("packaging/build/distributions/flix-spec-$version.jar"))
    systemProperty("runnerJar", rootProject.file("tools/conformance/build/libs/flix-spec-runner-$version.jar").absolutePath)
    systemProperty("specJar", rootProject.file("packaging/build/distributions/flix-spec-$version.jar").absolutePath)
}
