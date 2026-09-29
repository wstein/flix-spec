package flix.spec.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Version pins are intentionally separate and required: neither is inferred from the other. */
public abstract class FlixSpecExtension {
    public abstract Property<String> getRunnerVersion();
    public abstract Property<String> getSpecVersion();
    public abstract DirectoryProperty getActualDirectory();
    public abstract DirectoryProperty getSourceRoot();
    public abstract RegularFileProperty getProjectionMap();
    public abstract RegularFileProperty getAccepted();
    public abstract Property<Integer> getBaseline();
    public abstract Property<Integer> getRecoveryBaseline();
    public abstract Property<Integer> getDiagnosticBaseline();
    public abstract Property<Integer> getDepthFloor();
    public abstract Property<Integer> getRecoveryDepthFloor();
}
