package flix.spec.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.tasks.Sync;
import javax.inject.Inject;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

public abstract class FlixSpecPlugin implements Plugin<Project> {
    @Inject protected abstract ArchiveOperations getArchives();
    @Override public void apply(Project project) {
        project.getPluginManager().apply("base");
        project.getPluginManager().apply("jvm-toolchains");
        var extension = project.getExtensions().create("flixSpec", FlixSpecExtension.class);
        var runner = configuration(project, "flixSpecRunner");
        var bundle = configuration(project, "flixSpecBundle");
        // Lazy defaults leave dependency resolution entirely in the consumer's repository policy.
        runner.defaultDependencies(deps -> deps.add(project.getDependencies().create(
            "io.github.wstein:flix-spec-runner:" + extension.getRunnerVersion().get())));
        bundle.defaultDependencies(deps -> deps.add(project.getDependencies().create(
            "io.github.wstein:flix-spec:" + extension.getSpecVersion().get())));

        var specDirectory = project.getLayout().getBuildDirectory().dir("flix-spec/spec");
        var archives = getArchives();
        var prepare = project.getTasks().register("prepareFlixSpec", Sync.class, task -> {
            task.setGroup("verification");
            task.setDescription("Extract the pinned specification bundle, removing stale files.");
            task.from(bundle.getElements().map(files -> {
                if (files.size() != 1) throw new IllegalArgumentException("flixSpecBundle must resolve exactly one data jar");
                return archives.zipTree(files.iterator().next().getAsFile());
            }));
            task.into(specDirectory);
        });
        extension.getSourceRoot().convention(specDirectory);
        extension.getActualDirectory().convention(project.getLayout().getBuildDirectory().dir("flix-spec/actual"));
        extension.getBaseline().convention(0);
        extension.getRecoveryBaseline().convention(0);
        extension.getDiagnosticBaseline().convention(0);
        extension.getDepthFloor().convention(0);
        extension.getRecoveryDepthFloor().convention(0);
        var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        var check = project.getTasks().register("flixSpecCheck", FlixSpecCheck.class, task -> {
            task.setGroup("verification");
            task.setDescription("Compare consumer projections; emit JSON and offline HTML reports.");
            task.dependsOn(prepare);
            task.getRunnerClasspath().from(runner);
            task.getSpecRoot().set(specDirectory);
            task.getActualDirectory().set(extension.getActualDirectory());
            task.getSourceRoot().set(extension.getSourceRoot());
            task.getSourceFiles().from(extension.getSourceRoot().getAsFileTree().matching(pattern -> {
                pattern.include("**/*.flix");
                pattern.exclude(".git/**", ".gradle/**");
            }));
            task.getProjectionMap().set(extension.getProjectionMap());
            task.getAccepted().set(extension.getAccepted());
            task.getBaseline().set(extension.getBaseline());
            task.getRecoveryBaseline().set(extension.getRecoveryBaseline());
            task.getDiagnosticBaseline().set(extension.getDiagnosticBaseline());
            task.getDepthFloor().set(extension.getDepthFloor());
            task.getRecoveryDepthFloor().set(extension.getRecoveryDepthFloor());
            task.getJavaLauncher().set(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(21))));
            task.getJsonReport().convention(project.getLayout().getBuildDirectory().file("reports/flix-spec/report.json"));
            task.getHtmlReport().convention(project.getLayout().getBuildDirectory().file("reports/flix-spec/report.html"));
        });
        project.getTasks().named("check").configure(task -> task.dependsOn(check));
    }

    private Configuration configuration(Project project, String name) {
        return project.getConfigurations().create(name, config -> {
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setTransitive(false);
        });
    }
}
