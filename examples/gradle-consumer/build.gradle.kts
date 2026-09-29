plugins { id("io.github.wstein.flix-spec") }
repositories { maven(providers.gradleProperty("flixSpecRepository").get()) }

flixSpec {
    runnerVersion.set(providers.gradleProperty("flixSpecRunnerVersion"))
    specVersion.set(providers.gradleProperty("flixSpecDataVersion"))
    actualDirectory.set(layout.buildDirectory.dir("adapter-output"))
}

// Wiring test only: real consumers replace this task with their own parser adapter.
val emitAdapterProjections by tasks.registering(Sync::class) {
    dependsOn("prepareFlixSpec")
    from(layout.buildDirectory.dir("flix-spec/spec/fixtures/expected"))
    into(layout.buildDirectory.dir("adapter-output"))
}
tasks.named("flixSpecCheck") { dependsOn(emitAdapterProjections) }
