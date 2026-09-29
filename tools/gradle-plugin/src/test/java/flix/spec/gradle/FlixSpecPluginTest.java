package flix.spec.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FlixSpecPluginTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path setup() throws IOException {
        Path root = temporary.newFolder().toPath();
        // Deliberately different coordinate versions prove that the plugin does not couple the pins.
        artifact(root, "flix-spec-runner", "1.2.3", Path.of(System.getProperty("runnerJar")));
        artifact(root, "flix-spec", "4.5.6", Path.of(System.getProperty("specJar")));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'io.github.wstein.flix-spec' }
            repositories { maven { url = uri('repo') } }
            flixSpec {
                runnerVersion = '1.2.3'
                specVersion = '4.5.6'
                actualDirectory = layout.projectDirectory.dir('actual')
            }
            """);
        try (var zip = new ZipFile(System.getProperty("specJar"))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("fixtures/expected/")) {
                    Path output = root.resolve("actual").resolve(Path.of(entry.getName()).getFileName());
                    Files.createDirectories(output.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, output); }
                }
            }
        }
        return root;
    }

    private void artifact(Path root, String name, String version, Path jar) throws IOException {
        Path target = root.resolve("repo/io/github/wstein/" + name + "/" + version);
        Files.createDirectories(target);
        Files.copy(jar, target.resolve(name + "-" + version + ".jar"));
        Files.writeString(target.resolve(name + "-" + version + ".pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>io.github.wstein</groupId><artifactId>" + name +
            "</artifactId><version>" + version + "</version></project>");
    }

    private GradleRunner runner(Path root) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("check", "--configuration-cache", "--stacktrace");
    }

    @Test public void realArtifactsPassAndReuseConfigurationAndTaskState() throws IOException {
        Path root = setup();
        BuildResult first = runner(root).build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":flixSpecCheck").getOutcome());
        assertTrue(Files.readString(root.resolve("build/reports/flix-spec/report.html")).contains("oracle_conformance: pass"));
        BuildResult second = runner(root).build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":flixSpecCheck").getOutcome());

        Path actual = root.resolve("actual/hello.json");
        String original = Files.readString(actual);
        String mutated = original.replace("\"kind\":\"Root\"", "\"kind\":\"Expr.Binary\"");
        assertNotEquals(original, mutated);
        Files.writeString(actual, mutated);
        BuildResult failure = runner(root).buildAndFail();
        assertTrue(failure.getOutput().contains("runner exit 1"));
        assertTrue(Files.readString(root.resolve("build/reports/flix-spec/report.html")).contains("oracle_conformance: fail"));

        Files.writeString(actual, original.replace("\"schemaVersion\": 2", "\"schemaVersion\": 999"));
        BuildResult invalid = runner(root).buildAndFail();
        assertTrue(invalid.getOutput().contains("runner exit 2"));
        assertFalse(Files.exists(root.resolve("build/reports/flix-spec/report.html")));
    }

    @Test public void requiresBothVersionPins() throws IOException {
        Path root = setup();
        Path build = root.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace("runnerVersion = '1.2.3'", ""));
        BuildResult result = runner(root).buildAndFail();
        assertTrue(result.getOutput().contains("runnerVersion"));
    }
}
