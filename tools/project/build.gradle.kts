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

dependencies {
    implementation("org.scala-lang:scala-library:2.13.18")
    implementation("org.scala-lang:scala-reflect:2.13.18")
    implementation(files(oracleJar))
}

application {
    mainClass.set("spike.Extract")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-unchecked")
}

tasks.register<JavaExec>("extract") {
    description = "Emits a projected tree for a single .flix file: ./gradlew :tools:project:extract --args=path/to/file.flix"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("spike.Extract")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("listKinds") {
    description = "Enumerates every TreeKind via reflection over the pinned jar."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("spike.ListKinds")
    workingDir = rootProject.projectDir
}

tasks.matching { it.name in setOf("run", "extract", "listKinds") }.configureEach {
    (this as JavaExec).doFirst {
        check(oracleJar.asFile.exists()) {
            "Missing ${oracleJar.asFile}. Run tools/oracle/fetch.sh first."
        }
    }
}
