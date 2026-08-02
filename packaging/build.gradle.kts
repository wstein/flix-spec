// Packages the generated, consumable artifacts (pin.json, ast/, schemas/,
// fixtures/, corpus/corpus.json) as a Maven artifact for consumers who would
// rather add a dependency than vendor files by hand. Published to a Maven
// repository hosted on GitHub Pages (docs/PIN-BUMP.md, section "Publishing").
//
// Versioning (see docs/VERSIONING.md): plain semver, no decoration. major.minor
// track the upstream Flix line and patch is this repository's own revision
// counter, so `0.75.1` is derived from Flix 0.75.x and `0.75.2` is the next
// build of this repository against that line.
//
// The pin is deliberately NOT in the version string. A version can only ever
// advertise a pin, never enforce one, and this repository already had a consumer
// depending on fixtures from one Flix while testing against a checkout of
// another -- a mismatch no naming convention can catch. Enforcement is a
// comparison the consumer makes against pin.json, which ships in the artifact.
// What remains here is advertisement, done three ways that do not distort
// ordering: the FLIX-PIN-<tag> marker file, the POM properties below, and
// pin.json itself.
plugins {
    base
    `maven-publish`
}

val pinJson = groovy.json.JsonSlurper()
    .parse(rootProject.file("pin.json"))
    .let { it as Map<*, *> }

val flixTag = (pinJson["upstream"] as Map<*, *>)["tag"] as String
val flixVersion = flixTag.removePrefix("v")

// -PflixSpec.snapshot=true marks a floating, mutable build (main-branch CI);
// omitted (the default) for tagged releases, which must be immutable once
// published -- see the "Refuse to Republish" check in .github/workflows/pages.yml.
val isSnapshot = (findProperty("flixSpec.snapshot") as String?)?.toBoolean() ?: false

group = rootProject.group
version = rootProject.version.toString() + if (isSnapshot) "-SNAPSHOT" else ""

val assembled = layout.buildDirectory.dir("assembled")

val assembleArtifacts = tasks.register<Copy>("assembleArtifacts") {
    into(assembled)
    from(rootProject.file("pin.json"))
    from(rootProject.file("LICENSE.md"))
    from(rootProject.file("NOTICE.md"))
    into("ast") { from(rootProject.file("ast")) }
    into("schemas") { from(rootProject.file("schemas")) }
    into("fixtures") { from(rootProject.file("fixtures")) }
    into("corpus") { from(rootProject.file("corpus/corpus.json")) }
}

// A marker whose *name* carries the pin: visible in `jar tf`, greppable, and
// assertable without parsing anything. pin.json remains the authority; this makes
// the pin impossible to miss when looking at the artifact rather than into it.
val flixPinMarker = tasks.register("flixPinMarker") {
    val out = assembled.map { it.file("FLIX-PIN-$flixTag").asFile }
    outputs.file(out)
    doLast {
        out.get().writeText(
            "flix-spec is derived from flix/flix $flixTag\n" +
                "commit ${(pinJson["upstream"] as Map<*, *>)["commit"]}\n" +
                "See pin.json for the authoritative record, including the oracle jar digest.\n",
        )
    }
}

val artifactsJar = tasks.register<Jar>("artifactsJar") {
    dependsOn(assembleArtifacts, flixPinMarker)
    from(assembled)
    archiveBaseName.set("flix-spec")
}

// GitHub Packages credentials. Gradle does NOT read ~/.m2/settings.xml the way
// `mvn` does -- that file is a Maven-CLI concept, not a Gradle one -- so this
// resolves credentials from whichever of the two conventional sources is
// present: CI's own token (GITHUB_ACTOR/GITHUB_TOKEN, standard in Actions,
// scoped to this job by `permissions: packages: write`), or, for a manual
// local publish, the same <server id="github-maven"> entry `mvn` itself
// would use. Never both put in a committed file: the token from settings.xml
// is read at build time only and does not appear in build output or any
// tracked file.
fun mavenSettingsServerCredential(serverId: String): Pair<String?, String?> {
    val settingsFile = file(System.getProperty("user.home")).resolve(".m2/settings.xml")
    if (!settingsFile.exists()) return null to null

    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(settingsFile)
    val servers = doc.getElementsByTagName("server")
    for (i in 0 until servers.length) {
        val server = servers.item(i) as org.w3c.dom.Element
        val id = server.getElementsByTagName("id").item(0)?.textContent
        if (id == serverId) {
            val username = server.getElementsByTagName("username").item(0)?.textContent
            val password = server.getElementsByTagName("password").item(0)?.textContent
            return username to password
        }
    }
    return null to null
}

val (githubPackagesUsername, githubPackagesPassword) =
    if (System.getenv("GITHUB_TOKEN") != null) {
        System.getenv("GITHUB_ACTOR") to System.getenv("GITHUB_TOKEN")
    } else {
        mavenSettingsServerCredential("github-maven")
    }

publishing {
    publications {
        create<MavenPublication>("flixSpec") {
            groupId = project.group.toString()
            artifactId = "flix-spec"
            version = project.version.toString()
            artifact(artifactsJar)

            pom {
                name.set("flix-spec")
                description.set(
                    "Shared test infrastructure for parsers of Flix: a TreeKind inventory, " +
                        "a pinned corpus definition, and projected-tree fixtures, all derived " +
                        "from the reference compiler at flix/flix $flixTag.",
                )
                url.set("https://github.com/wstein/flix-spec")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/wstein/flix-spec")
                }
                // Structured provenance, readable without downloading the jar. The version no
                // longer carries the pin, so this is where a resolver-side tool or a human
                // reviewing a dependency graph finds it.
                properties.set(
                    mapOf(
                        "flix.tag" to flixTag,
                        "flix.commit" to (pinJson["upstream"] as Map<*, *>)["commit"].toString(),
                        "flix.treeHash" to (pinJson["upstream"] as Map<*, *>)["treeHash"].toString(),
                        "flix.oracleSha256" to (pinJson["oracleArtifact"] as Map<*, *>)["sha256"].toString(),
                    ),
                )
            }
        }
    }

    repositories {
        maven {
            name = "flixSpecRepo"
            // Default: a scratch directory under build/ for local testing.
            // CI overrides this to point at a checked-out gh-pages worktree.
            url = uri(
                (findProperty("flixSpec.publishRepo") as String?)
                    ?: layout.buildDirectory.dir("repo").get().asFile.toURI().toString(),
            )
        }

        // Opt-in: only registered when credentials are actually resolvable, so a
        // plain local `publish` (testing the Pages flow) doesn't also attempt an
        // authenticated call and fail. See docs/VERSIONING.md, "GitHub Packages".
        if (githubPackagesUsername != null && githubPackagesPassword != null) {
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/wstein/flix-spec")
                credentials {
                    username = githubPackagesUsername
                    password = githubPackagesPassword
                }
            }
        }
    }
}
