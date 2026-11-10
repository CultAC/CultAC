package ac.cult.cultac.checks;

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

        private Builder() {}

        public CheckInfo build() {
            return new CheckInfo(this);
        }
    }
}
