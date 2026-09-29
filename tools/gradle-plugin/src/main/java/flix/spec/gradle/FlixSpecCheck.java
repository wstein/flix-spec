package flix.spec.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Reports are inexpensive to regenerate; preserve local runner failure diagnostics")
public abstract class FlixSpecCheck extends DefaultTask {
    @Classpath public abstract ConfigurableFileCollection getRunnerClasspath();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSpecRoot();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getActualDirectory();
    @Internal public abstract DirectoryProperty getSourceRoot();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getSourceFiles();
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getProjectionMap();
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getAccepted();
    @Input public abstract Property<Integer> getBaseline();
    @Input public abstract Property<Integer> getRecoveryBaseline();
    @Input public abstract Property<Integer> getDiagnosticBaseline();
    @Input public abstract Property<Integer> getDepthFloor();
    @Input public abstract Property<Integer> getRecoveryDepthFloor();
    @Nested public abstract Property<JavaLauncher> getJavaLauncher();
    @OutputFile public abstract RegularFileProperty getJsonReport();
    @OutputFile public abstract RegularFileProperty getHtmlReport();
    @Inject protected abstract ExecOperations getExecOperations();

    private int invoke(List<String> arguments) {
        return getExecOperations().javaexec(exec -> {
            exec.setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
            exec.setClasspath(getRunnerClasspath());
            exec.getMainClass().set("flix.spec.Runner");
            exec.args(arguments);
            exec.setIgnoreExitValue(true);
        }).getExitValue();
    }

    private void optional(List<String> args, String flag, RegularFileProperty file) {
        if (file.isPresent()) {
            args.add(flag);
            args.add(file.get().getAsFile().getAbsolutePath());
        }
    }

    @TaskAction public void check() throws IOException {
        var json = getJsonReport().get().getAsFile().toPath();
        var html = getHtmlReport().get().getAsFile().toPath();
        if (json.equals(html)) throw new GradleException("JSON and HTML report paths must differ");
        Files.createDirectories(json.toAbsolutePath().getParent());
        Files.createDirectories(html.toAbsolutePath().getParent());
        Files.deleteIfExists(json);
        Files.deleteIfExists(html);
        List<String> args = new ArrayList<>(List.of(
            "--spec-root", getSpecRoot().get().getAsFile().getAbsolutePath(),
            "--source-root", getSourceRoot().get().getAsFile().getAbsolutePath(),
            "--actual", getActualDirectory().get().getAsFile().getAbsolutePath(),
            "--report", json.toAbsolutePath().toString(),
            "--baseline", getBaseline().get().toString(),
            "--recovery-baseline", getRecoveryBaseline().get().toString(),
            "--diagnostic-baseline", getDiagnosticBaseline().get().toString(),
            "--depth-floor", getDepthFloor().get().toString(),
            "--recovery-depth-floor", getRecoveryDepthFloor().get().toString()));
        optional(args, "--map", getProjectionMap());
        optional(args, "--accepted", getAccepted());
        int status = invoke(args);
        if (Files.isRegularFile(json)) {
            int rendered = invoke(List.of("render", "--report", json.toAbsolutePath().toString(), "--html", html.toAbsolutePath().toString()));
            if (rendered != 0) throw new GradleException("HTML rendering failed (exit " + rendered + "); comparison exit was " + status);
        }
        if (status == 1) throw new GradleException("Flix conformance failed (runner exit 1); see " + json);
        if (status != 0) throw new GradleException("Flix runner input/execution failure (runner exit " + status + ")");
    }
}
