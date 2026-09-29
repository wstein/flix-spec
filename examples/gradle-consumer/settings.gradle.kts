pluginManagement {
    repositories { maven(providers.gradleProperty("flixSpecRepository").get()) }
    plugins { id("io.github.wstein.flix-spec") version providers.gradleProperty("flixSpecPluginVersion").get() }
}
rootProject.name = "published-flix-spec-consumer"
