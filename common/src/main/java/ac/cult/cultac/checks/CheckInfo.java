package ac.cult.cultac.checks;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Immutable runtime identity and config defaults for a check, passed to the check's
 * constructor instead of being declared on an annotation. Instances are created through
 * {@link #builder()}; every attribute except {@code name} has a fallback.
 */
@Getter
public final class CheckInfo {
    private static final String FALLBACK_DESCRIPTION = "No description provided";

    private final String name;
    /** {@link #getName()} with the names the check had in older name versions. */
    private final CheckNames names;
    /** Display alias, or {@code null} when the check has no alternate name. */
    private final String altName;
    /** Config section key; {@code null} means "derive it from {@link #getName()}". */
    private final String configName;

    private final String stableKey;
    private final String description;
    private final double decay;
    /** Violation level above which the check may set the player back. */
    private final double setback;
    /** Whether the check is still experimental. */
    private final boolean experimental;

    private CheckInfo(Builder builder) {
        if (builder.name == null || builder.name.isEmpty()) {
            throw new IllegalArgumentException("CheckInfo requires a non-empty name");
        }
        this.name = builder.name;
        this.names = builder.names();
        this.altName = builder.altName;
        this.configName = builder.configName;
        this.stableKey = builder.stableKey;
        this.description = builder.description != null ? builder.description : FALLBACK_DESCRIPTION;
        this.decay = builder.decay;
        this.setback = builder.setback;
        this.experimental = builder.experimental;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Setter
    @Accessors(fluent = true)
    public static final class Builder {
        private String name;
        private String altName;
        private String configName;
        private String stableKey = "";
        private String description;
        private double decay = 0.05;
        private double setback = 25;
        private boolean experimental;

        private final List<Former> formerNames = new ArrayList<>();

        private record Former(int lastVersion, String name) {}

        private Builder() {}

        /** The check was {@code name} up to name version {@code lastVersion}; see {@link CheckNames}. */
        public Builder formerName(int lastVersion, String name) {
            formerNames.add(new Former(lastVersion, name));
            return this;
        }

        private CheckNames names() {
            CheckNames names = CheckNames.of(name);
            for (Former former : formerNames) names = names.formerly(former.lastVersion(), former.name());
            return names;
        }

        public CheckInfo build() {
            return new CheckInfo(this);
        }
    }
}
