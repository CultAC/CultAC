package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.grim.grimac.api.AbstractCheck;

/**
 * A proxy check as listed in CHECK_CATALOG. Violations follow FLAG messages; {@link #setEnabled} changes only this
 * backend's view and never the proxy check.
 */
public final class CompanionCheck implements AbstractCheck {
    private final CheckInfo info;
    private volatile boolean enabled;
    private volatile double violations;
    private volatile long lastViolationTime;

    CompanionCheck(CheckInfo info) {
        this.info = info;
        this.enabled = info.enabled();
    }

    int index() {
        return info.idx();
    }

    void setViolations(double violations) {
        this.violations = violations;
    }

    void commitFlag(double violations, long time) {
        this.violations = violations;
        this.lastViolationTime = time;
    }

    @Override
    public String getCheckName() {
        return info.checkName();
    }

    @Override
    public String getAlternativeName() {
        return info.alternativeName();
    }

    @Override
    public String getDescription() {
        return info.description();
    }

    @Override
    public String getStableKey() {
        return info.stableKey();
    }

    @Override
    public String getConfigName() {
        return info.configName();
    }

    @Override
    public double getViolations() {
        return violations;
    }

    @Override
    public long getLastViolationTime() {
        return lastViolationTime;
    }

    @Override
    public double getDecay() {
        return info.decay();
    }

    @Override
    public double getSetbackVL() {
        return info.setbackVL();
    }

    @Override
    public boolean isExperimental() {
        return info.experimental();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void reload() {}
}
