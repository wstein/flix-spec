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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.scalatest:scalatest_2.13:3.2.19")
    testImplementation("org.scalatestplus:junit-4-13_2.13:3.2.19.0")
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
    mainClass.set("flix.spec.TreeKindExtractor")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("generateTreeKind") {
    description = "Generates ast/treekind.json from reflection over the pinned jar."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("flix.spec.TreeKindExtractor")
    args = listOf(rootProject.layout.projectDirectory.file("ast/treekind.json").asFile.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.matching { it.name in setOf("run", "extract", "listKinds", "generateTreeKind") }.configureEach {
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
