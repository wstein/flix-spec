// The projection emitter (implementation plan section 4.1/4.2, Route B).
// Depends on the pinned flix.jar as a plain external jar -- never recompiles
// Flix. Run tools/oracle/fetch.sh first to populate .oracle/flix.jar.
plugins {
    scala
    application
}

repositories {
    mavenCentral()
}

val oracleJar = rootProject.layout.projectDirectory.file(".oracle/flix.jar")

// The Scala version is not ours to choose: it must match the oracle jar, which is
// compiled with Scala 2.13.18. Read it from pin.json so the two cannot drift and so
// no dependency bot can "upgrade" it -- Dependabot proposed scala-library 3.8.4,
// which is the Scala 3 standard library and does not compile against this jar.
val oracleScalaVersion: String = groovy.json.JsonSlurper()
    .parse(rootProject.layout.projectDirectory.file("pin.json").asFile)
    .let { it as Map<*, *> }
    .let { it["buildProvenance"] as Map<*, *> }
    .let { it["scalaVersion"] as String }

dependencies {
    implementation("org.scala-lang:scala-library:$oracleScalaVersion")
    // No scala-reflect: TreeKind enumeration reads jar entries and decides via java.lang.Class,
    // so knownDirectSubclasses (direct-only, documented as unreliable) is not needed and the
    // dependency surface stays at scala-library + the pinned oracle jar.
    implementation(files(oracleJar))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.scalatest:scalatest_2.13:3.2.19")
    testImplementation("org.scalatestplus:junit-4-13_2.13:3.2.19.0")
}

application {
    mainClass.set("flix.spec.ProjectionExtractor")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-unchecked")
}

tasks.register<JavaExec>("extract") {
    description = "Emits a projected tree for a single .flix file: ./gradlew :tools:project:extract --args=path/to/file.flix"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("flix.spec.ProjectionExtractor")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("proposeTreeKind") {
    description =
        "Reports the TreeKind count and digest for the pinned jar without asserting or writing. " +
            "Use during a pin bump, before updating pin.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("flix.spec.TreeKindExtractor")
    args = listOf("--propose")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("generateTreeKind") {
    description = "Generates ast/treekind.json from reflection over the pinned jar."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("flix.spec.TreeKindExtractor")
    args = listOf(rootProject.layout.projectDirectory.file("ast/treekind.json").asFile.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.matching { it.name in setOf("run", "extract", "proposeTreeKind", "generateTreeKind") }.configureEach {
    (this as JavaExec).doFirst {
        check(oracleJar.asFile.exists()) {
            "Missing ${oracleJar.asFile}. Run tools/oracle/fetch.sh first."
        }
    }
}

tasks.withType<Test> {
    useJUnit()
    doFirst {
        check(oracleJar.asFile.exists()) {
            "Missing ${oracleJar.asFile}. Run tools/oracle/fetch.sh first."
        }
    }
}
