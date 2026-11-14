package ac.cult.cultac.checks;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.verdict.FlagArbiter;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseBuf;
import ac.grim.grimac.api.storage.verbose.VerboseRenderContext;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Class from https://github.com/Tecnio/AntiCheatBase/blob/master/src/main/java/me/tecnio/anticheat/check/Check.java
@Getter
public class Check extends CultProcessor implements AbstractCheck {
    private static final FlagEvent.Channel FLAG_CHANNEL =
            CultAPI.INSTANCE.getEventBus().get(FlagEvent.class);

    // violations
    public double violations;
    private long lastViolationTime;
    private boolean lastFlagStoredBinaryVerbose;
    private final VerboseBuf verbose = new VerboseBuf();

    // Touched only on the player's packet thread, and only while a FlagArbiter defers flags.
    @Getter(AccessLevel.NONE)
    private int deferredFlags;

    // check data
    private final @Nullable String checkName;
    private final @Nullable String configName;
    private final @Nullable String alternativeName;
    private final @NotNull String stableKey;
    private final boolean experimental;
    private final @NotNull String defaultDescription;
    private final double defaultDecay;
    private final double defaultSetbackVL;

    // configurable
    private @MonotonicNonNull String displayName;
    private @MonotonicNonNull String description;
    private double decay;
    private double setbackVL;

    @Setter
    private boolean isEnabled;

    // permissions
    private boolean exemptPermission;
    private boolean noSetbackPermission;
    private boolean noModifyPacketPermission;

    public Check(final @NotNull CultPlayer player) {
        super(Objects.requireNonNull(player, "player"));

        final CheckData checkData = this.getClass().getAnnotation(CheckData.class);
        if (checkData != null) {
            this.checkName = checkData.name();
            this.configName = configName(this.checkName, checkData.configName());
            this.defaultDecay = checkData.decay();
            this.defaultSetbackVL = checkData.setback();
            this.alternativeName = checkData.alternativeName();
            this.experimental = checkData.experimental();
            this.defaultDescription = checkData.description();
            this.stableKey = checkData.stableKey();
            this.displayName = this.checkName;
        } else {
            this.defaultDescription = CheckData.DEFAULT_DESCRIPTION;
            this.defaultDecay = CheckData.DEFAULT_DECAY;
            this.defaultSetbackVL = CheckData.DEFAULT_SETBACK;
            this.stableKey = "";
            this.alternativeName = null;
            this.checkName = null;
            this.configName = null;
            this.experimental = false;
        }

        reload();
    }

    /** Identity and defaults supplied at runtime instead of through {@link CheckData}. */
    public Check(final @NotNull CultPlayer player, final @NotNull CheckInfo checkInfo) {
        super(Objects.requireNonNull(player, "player"));
        Objects.requireNonNull(checkInfo, "checkInfo");

        this.checkName = checkInfo.getName();
        this.configName = configName(this.checkName, checkInfo.getConfigName());
        this.defaultDecay = checkInfo.getDecay();
        this.defaultSetbackVL = checkInfo.getSetback();
        this.alternativeName = checkInfo.getAltName();
        this.experimental = checkInfo.isExperimental();
        this.defaultDescription = checkInfo.getDescription();
        this.stableKey = Objects.requireNonNull(checkInfo.getStableKey(), "checkInfo.stableKey");
        this.displayName = this.checkName;

        reload();
    }

    /** The prefix of a check's config keys: the declared config name, or the check name when it declares none. */
    public static @NotNull String configName(@NotNull String checkName, @Nullable String declared) {
        return declared == null || declared.equals("DEFAULT") ? checkName : declared;
    }

    /** Whether this check may run for Bedrock-platform players. */
    public boolean isBedrockSupported() {
        return getClass().isAnnotationPresent(BedrockSupported.class);
    }

    public boolean shouldModifyPackets() {
        return isEnabled
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                && !player.isDisabled()
                && !player.noModifyPacketPermission
                && !noModifyPacketPermission
                && !exemptPermission;
    }

    /**
     * Implementations must only depend on immutable connection properties.
     */
    public boolean isApplicable() {
        return true;
    }

    public final void updatePermissions() {
        if (configName == null) return;
        final String id = permissionId(configName);
        exemptPermission = player.hasPermission("cult.exempt." + id);
        noSetbackPermission = player.hasPermission("cult.nosetback." + id);
        noModifyPacketPermission = player.hasPermission("cult.nomodifypacket." + id);
    }

    /** The lower-case check id in this check's permission nodes, whatever the default locale. */
    public static String permissionId(String configName) {
        return configName.toLowerCase(Locale.ROOT);
    }

    public final boolean flag() {
        return flag("");
    }

    /**
     * Records a violation and alerts, unless the check is exempt or a FlagEvent handler cancels the flag.
     *
     * <p>When the player's {@link FlagArbiter} defers the flag, this returns true at once and the violation,
     * punishments, storage and alert apply only when the verdict commits it. Setbacks and anything else a caller does
     * on the return value act at once, counting the flag toward the setback VL; a cancelling verdict cannot undo them.
     */
    public boolean flag(String verbose) {
        return flag(constant(verbose));
    }

    /** Flags with verbose text that is rendered only if a flag consumer or alert reads it. */
    public boolean flag(@NotNull Supplier<String> verbose) {
        Supplier<String> alertText = memoize(Objects.requireNonNull(verbose, "verbose"));
        return recordFlag(alertText, alertText);
    }

    /**
     * Flags with {@code verbose} for flag consumers and the flag history, and {@code alertText} for alerts, verbose,
     * webhooks, proxy forwards and punishment commands. Each is rendered only if something reads it.
     */
    public boolean flag(@NotNull Supplier<String> verbose, @NotNull Supplier<String> alertText) {
        return recordFlag(
                memoize(Objects.requireNonNull(verbose, "verbose")),
                memoize(Objects.requireNonNull(alertText, "alertText")));
    }

    /**
     * Flags exactly as {@link #flag()} does for every flag consumer and alert, and gives the player's observer
     * {@code recorded} as the flag's verbose. Call it only when {@link #observerRecordsVerbose()} is true.
     */
    protected final boolean flagRecorded(@NotNull Supplier<String> recorded) {
        Supplier<String> empty = memoize(constant(""));
        return recordFlag(empty, empty, memoize(Objects.requireNonNull(recorded, "recorded")));
    }

    /** Whether the player's observer records a verbose of this check's flags that no other consumer reads. */
    protected final boolean observerRecordsVerbose() {
        PlayerObserver observer = player.observer;
        return observer != null && observer.recordsVerbose(this);
    }

    public final boolean flag(@NotNull Verbose.Writer verbose) {
        BinaryVerbose binary = lazyVerbose(verbose);
        return recordFlag(binary, binary.rendered());
    }

    public final boolean flag(@NotNull Verbose.Writer verbose, @NotNull Supplier<String> alertText) {
        BinaryVerbose binary = lazyVerbose(verbose);
        return recordFlag(binary, alertText);
    }

    /** Whether a flag of this check reaches its consumers: false while the player is disabled or exempt from it. */
    public final boolean canRecordFlags() {
        return !player.isDisabled() && (!experimental || player.isExperimentalChecks()) && !exemptPermission;
    }

    private boolean recordFlag(@NotNull Supplier<String> verbose, @NotNull Supplier<String> alertText) {
        return recordFlag(verbose, alertText, null);
    }

    /** {@code recorded} is the verbose only the player's observer reads; null gives it {@code verbose}. */
    private boolean recordFlag(
            @NotNull Supplier<String> verbose,
            @NotNull Supplier<String> alertText,
            @Nullable Supplier<String> recorded) {
        if (!canRecordFlags()) return false; // Avoid calling event if disabled

        if (FLAG_CHANNEL.fire(RuntimeEnvironment.outwardUser(player), RuntimeEnvironment.outwardCheck(this), verbose))
            return false;

        FlagArbiter arbiter = player.getFlagArbiter();
        if (arbiter == null || !defer(arbiter, verbose, null, alertText, recorded)) {
            commitFlag(null, verbose, alertText, recorded);
        }
        return true;
    }

    private boolean recordFlag(@NotNull BinaryVerbose verbose, @NotNull Supplier<String> alertText) {
        Supplier<String> rendered = verbose.rendered();
        byte[] verboseData = verbose.data();

        if (!canRecordFlags()) return false; // Avoid calling event if disabled

        if (FLAG_CHANNEL.fire(
                RuntimeEnvironment.outwardUser(player),
                RuntimeEnvironment.outwardCheck(this),
                RuntimeEnvironment.outwardVerbose(rendered))) return false;

        FlagArbiter arbiter = player.getFlagArbiter();
        if (arbiter == null || !defer(arbiter, rendered, verboseData, alertText, null)) {
            commitFlag(verboseData, rendered, alertText, null);
        }
        return true;
    }

    /**
     * {@code verboseData} is null for a text verbose, which stores no binary flag row; {@code recorded} is null unless
     * the observer reads a verbose of its own.
     */
    private void commitFlag(
            byte @Nullable [] verboseData,
            @NotNull Supplier<String> verbose,
            @NotNull Supplier<String> alertText,
            @Nullable Supplier<String> recorded) {
        lastFlagStoredBinaryVerbose = verboseData != null;
        player.punishmentManager.handleViolation(this);
        lastViolationTime = System.currentTimeMillis();
        violations++;
        PlayerObserver observer = player.observer;
        if (observer != null) observer.flagged(this, violations, recorded != null ? recorded : verbose);
        if (verboseData != null) {
            CultAPI.INSTANCE
                    .getDataStoreLifecycle()
                    .liveWriteHooks()
                    .recordFlagDataFromCheck(player, this, violations, verboseData);
        }
        player.punishmentManager.handleAlert(player, memoize(alertText), memoize(verbose), this);
    }

    private boolean defer(
            @NotNull FlagArbiter arbiter,
            @NotNull Supplier<String> verbose,
            byte @Nullable [] verboseData,
            @NotNull Supplier<String> alertText,
            @Nullable Supplier<String> recorded) {
        double prospective = violations + deferredFlags + 1;
        Supplier<String> alert = memoize(alertText);
        if (!arbiter.defer(this, verbose, prospective, new DeferredFlag(verboseData, verbose, alert, recorded)))
            return false;
        deferredFlags++;
        // The verdict commits after this prediction's listeners, too late for a Cult Log identifier the alert shows to
        // build its log. Render the alert now if committing could render it.
        if (player.punishmentManager.mayRenderVerbose(this, deferredFlags)) alert.get();
        return true;
    }

    private @NotNull BinaryVerbose lazyVerbose(@NotNull Verbose.Writer writer) {
        Objects.requireNonNull(writer, "writer");
        byte[] verboseData = writer.end().toByteArray();
        Verbose template = writer.verbose();
        Supplier<String> rendered = memoize(() -> template.render(
                verboseData,
                new VerboseRenderContext(
                        player.getClientVersion().getProtocolVersion(),
                        CultAPI.INSTANCE.getPlatformServer().getPlatformImplementationString())));
        return new BinaryVerbose(verboseData, rendered);
    }

    protected final @NotNull VerboseBuf verbose() {
        return verbose;
    }

    public final boolean flagWithSetback() {
        return flagWithSetback("");
    }

    public final boolean flagWithSetback(String verbose) {
        if (flag(verbose)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final boolean flagWithSetback(@NotNull Verbose.Writer verbose) {
        if (flag(verbose)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final boolean flagWithSetback(@NotNull Verbose.Writer verbose, @NotNull Supplier<String> alertText) {
        if (flag(verbose, alertText)) {
            setbackIfAboveSetbackVL();
            return true;
        }
        return false;
    }

    public final void reward() {
        violations = Math.max(0, violations - decay);
    }

    @Override
    public final void reload(@NotNull ConfigManager configuration) {
        if (configName != null) {
            decay = configuration.getDoubleElse(configName + ".decay", defaultDecay);
            setbackVL = configuration.getDoubleElse(configName + ".setbackvl", defaultSetbackVL);
            displayName = configuration.getStringElse(configName + ".displayname", checkName);
            description = configuration.getStringElse(configName + ".description", defaultDescription);

            if (setbackVL == -1) setbackVL = Double.MAX_VALUE;
        }
        onReload(configuration);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {}

    public boolean alert(@NotNull Supplier<String> verbose) {
        return player.punishmentManager.handleAlert(player, memoize(Objects.requireNonNull(verbose, "verbose")), this);
    }

    public boolean setbackIfAboveSetbackVL() {
        if (shouldSetback()) {
            return player.getSetbackTeleportUtil().executeViolationSetback(this);
        }
        return false;
    }

    public boolean setbackIfAboveSetbackVLNonSimulating() {
        if (shouldSetback()) {
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            return true;
        }
        return false;
    }

    public boolean shouldSetback() {
        return !noSetbackPermission
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                && violations + deferredFlags > setbackVL;
    }

    public boolean executeViolationSetback() {
        return !noSetbackPermission && player.getSetbackTeleportUtil().executeViolationSetback(this);
    }

    public String formatOffset(double offset) {
        return offset > 0.001 ? String.format("%.5f", offset) : String.format("%.2E", offset);
    }

    private static @NotNull Supplier<String> constant(String verbose) {
        String value = verbose == null ? "" : verbose;
        return () -> value;
    }

    private static @NotNull Supplier<String> memoize(@NotNull Supplier<String> supplier) {
        return new Supplier<>() {
            private String value;
            private boolean computed;

            @Override
            public synchronized String get() {
                if (!computed) {
                    try {
                        value = supplier.get();
                        if (value == null) value = "";
                    } catch (RuntimeException ignored) {
                        value = "";
                    }
                    computed = true;
                }
                return value;
            }
        };
    }

    private final class DeferredFlag implements FlagOutcome {
        private final byte @Nullable [] verboseData;
        private final @NotNull Supplier<String> verbose;
        private final @NotNull Supplier<String> alertText;
        private final @Nullable Supplier<String> recorded;

        private DeferredFlag(
                byte @Nullable [] verboseData,
                @NotNull Supplier<String> verbose,
                @NotNull Supplier<String> alertText,
                @Nullable Supplier<String> recorded) {
            this.verboseData = verboseData;
            this.verbose = verbose;
            this.alertText = alertText;
            this.recorded = recorded;
        }

        @Override
        public void commit() {
            deferredFlags--;
            commitFlag(verboseData, verbose, alertText, recorded);
        }

        @Override
        public void discard() {
            deferredFlags--;
        }
    }

    private record BinaryVerbose(
            byte @NotNull [] data, @NotNull Supplier<String> rendered) {}
}
