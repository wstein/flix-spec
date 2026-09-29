plugins {
    scala
    application
    `maven-publish`
}

repositories { mavenCentral() }

// Deliberately independent of pin.json and the oracle's compile/runtime classpath.
dependencies {
    implementation("org.scala-lang:scala-library:2.13.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.scalatest:scalatest_2.13:3.2.20")
    testImplementation("org.scalatestplus:junit-4-13_2.13:3.2.20.0")
}

group = rootProject.group
version = rootProject.version.toString() +
    if (providers.gradleProperty("flixSpec.snapshot").orNull == "true") "-SNAPSHOT" else ""

application { mainClass.set("flix.spec.Runner") }

tasks.processResources { from(rootProject.file("schemas/conformance-report.schema.json")) }
tasks.processResources { from(rootProject.file("schemas/projection-map.schema.json")) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

val runnerJar = tasks.register<Jar>("runnerJar") {
    archiveBaseName.set("flix-spec-runner")
    manifest.attributes("Main-Class" to "flix.spec.Runner", "Implementation-Version" to project.version)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    from(rootProject.file("LICENSE.md"))
}
tasks.named("assemble") { dependsOn(runnerJar) }

publishing {
    publications {
        create<MavenPublication>("runner") {
            artifactId = "flix-spec-runner"
            artifact(runnerJar)
            pom {
                name.set("flix-spec-runner")
                description.set("Standalone oracle-free Flix parser conformance runner; requires Java 21.")
                url.set("https://github.com/wstein/flix-spec")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
            }
        }
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

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-unchecked")
}

tasks.withType<Test> {
    useJUnit()
    inputs.file(rootProject.file("pin.json"))
    listOf("ast", "schemas", "fixtures", "defects").forEach { inputs.dir(rootProject.file(it)) }
    inputs.file(rootProject.file("corpus/corpus.json"))
}
