package ac.cult.cultac.checks;

/**
 * Immutable runtime identity and config defaults for a check, passed to the check's
 * constructor instead of being declared on an annotation. Instances are created through
 * {@link #builder()}; every attribute except {@code name} has a fallback.
 */
public final class CheckInfo {
    private static final String FALLBACK_DESCRIPTION = "No description provided";

    private final String name;
    private final String altName;
    private final String configName;
    private final String stableKey;
    private final String description;
    private final double decay;
    private final double setback;
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

    public String getName() {
        return name;
    }

    /** Display alias, or {@code null} when the check has no alternate name. */
    public String getAltName() {
        return altName;
    }

    /** Config section key; {@code null} means "derive it from {@link #getName()}". */
    public String getConfigName() {
        return configName;
    }

    public String getStableKey() {
        return stableKey;
    }

    public String getDescription() {
        return description;
    }

    public double getDecay() {
        return decay;
    }

    /** Violation level above which the check may set the player back. */
    public double getSetback() {
        return setback;
    }

    public boolean isExperimental() {
        return experimental;
    }

    public static final class Builder {
        private String name;
        private String altName;
        private String configName;
        private String stableKey = "";
        private String description;
        private double decay = 0.05;
        private double setback = 25;
        private boolean experimental;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder altName(String altName) {
            this.altName = altName;
            return this;
        }

        public Builder configName(String configName) {
            this.configName = configName;
            return this;
        }

        public Builder stableKey(String stableKey) {
            this.stableKey = stableKey;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder decay(double decay) {
            this.decay = decay;
            return this;
        }

        public Builder setback(double setback) {
            this.setback = setback;
            return this;
        }

        public Builder experimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        public CheckInfo build() {
            return new CheckInfo(this);
        }
    }
}
