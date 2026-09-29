plugins {
    scala
    application
}

repositories { mavenCentral() }

// Deliberately independent of pin.json and the oracle's compile/runtime classpath.
dependencies {
    implementation("org.scala-lang:scala-library:2.13.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.scalatest:scalatest_2.13:3.2.20")
    testImplementation("org.scalatestplus:junit-4-13_2.13:3.2.20.0")
}

application { mainClass.set("flix.spec.Conformance") }

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature", "-unchecked")
}

tasks.withType<Test> {
    useJUnit()
    inputs.file(rootProject.file("pin.json"))
    listOf("ast", "schemas", "fixtures", "defects").forEach { inputs.dir(rootProject.file(it)) }
    inputs.file(rootProject.file("corpus/corpus.json"))
}
