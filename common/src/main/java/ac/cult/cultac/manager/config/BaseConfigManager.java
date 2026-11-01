package ac.cult.cultac.manager.config;

import ac.cult.cultac.bridge.protocol.CommandRoots;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/*
 * This is to hold whatever config manager was set via the reload method in the API
 * and any global variables that are the same between players.
 */
public class BaseConfigManager {

    public static final String OBSERVE_ONLY_ENV = "CULTAC_OBSERVE_ONLY";
    public static final String OBSERVE_ONLY_KEY = "observe-only";

    private final List<Pattern> ignoredClientPatterns = new ArrayList<>();

    @Getter
    private ConfigManager config = null;

    @Getter
    private boolean observeOnly = false;

    @Getter
    private String prefix = "&bCult &8»";

    @Getter
    private String webhookNotEnabled;

    @Getter
    private String webhookTestMessage;

    @Getter
    private String webhookTestSucceeded;

    @Getter
    private String webhookTestFailed;

    @Getter
    private String disconnectTimeout;

    @Getter
    private String disconnectClosed;

    @Getter
    private String disconnectPacketError;

    @Getter
    private boolean disablePongCancelling;

    private boolean launchFailOpen = true;

    @Getter
    private int updatePermissionTicks = -1;

    @Getter
    private int maxPingKnockback = 1000;
    // cult.client-brand.strip-pattern regex; null when disabled (empty key)
    private Pattern brandRemover = null;

    // Bedrock movement reporting and tolerance options.
    @Getter
    private boolean verboseBedrockMovement = false;

    @Getter
    private boolean verboseBedrockMovementLogCleanOffsets = false;

    @Getter
    private double verboseBedrockMovementMinOffset = 1.0E-7D;

    @Getter
    private double verboseBedrockMovementCooldownSeconds = 10.0D;

    @Getter
    private double bedrockMovementPositionFlagThreshold = 0.001D;

    @Getter
    private double bedrockMovementPositionReconciliationStep = 0.0005D;

    @Getter
    private double bedrockMovementVelocityFlagThreshold = 0.001D;

    @Getter
    private boolean bedrockMovementSetbacksEnabled = true;

    private static double nonNegativeElse(ConfigManager config, String key, double fallback) {
        double value = config.getDoubleElse(key, fallback);
        return value >= 0 ? value : fallback;
    }

    private static double nonNegativeFiniteElse(ConfigManager config, String key, double fallback) {
        double value = config.getDoubleElse(key, fallback);
        return Double.isFinite(value) && value >= 0 ? value : fallback;
    }

    /** Whether a failure of the anticheat itself leaves the player connected and uninspected. */
    public boolean failsOpen() {
        return isObserveOnly() || launchFailOpen;
    }

    // initialize the config
    public void load(ConfigManager config) {
        boolean reloading = this.config != null;
        boolean wasObserveOnly = observeOnly;
        this.config = config;

        int configuredMaxTransactionTime = config.getIntElse("max-transaction-time", 60);
        if (configuredMaxTransactionTime > 180 || configuredMaxTransactionTime < 1) {
            LogUtil.warn(
                    "Detected invalid max-transaction-time! This setting is clamped between 1 and 180 to prevent issues. Attempting to disable or set this too high can result in memory usage issues.");
        }

        ignoredClientPatterns.clear();
        List<String> ignoredClients = config.getStringList("client-brand.ignored-clients");
        if (ignoredClients != null) {
            for (String string : ignoredClients) {
                try {
                    ignoredClientPatterns.add(Pattern.compile(string));
                } catch (PatternSyntaxException e) {
                    throw new RuntimeException("Failed to compile client pattern", e);
                }
            }
        }

        observeOnly = resolveObserveOnly(config, System::getenv);
        if (observeOnly) {
            LogUtil.info("Observe-only mode: checks flag and alert; nothing acts on the player or their packets");
        }
        if (reloading && observeOnly != wasObserveOnly) {
            LogUtil.info(
                    observeOnly
                            ? "Switched to observe-only; pending setbacks are released"
                            : "Switched to enforcing; check violations start from zero");
        }

        prefix = config.getStringElse("prefix", "&bCult &8»");

        webhookNotEnabled = config.getStringElse("webhook-not-enabled", "Discord webhooks are not enabled!");
        webhookTestMessage = config.getStringElse("webhook-test-message", "test message");
        webhookTestSucceeded = config.getStringElse("webhook-test-succeeded", "Discord webhook test succeeded!");
        webhookTestFailed = config.getStringElse("webhook-test-failed", "Discord webhook test failed!");
        disconnectTimeout = config.getStringElse("disconnect.timeout", "<lang:disconnect.timeout>");
        disconnectClosed = config.getStringElse("disconnect.closed", "<lang:disconnect.timeout>");
        disconnectPacketError = config.getStringElse(
                "disconnect.error",
                "<red>An error occurred whilst processing packets. Please contact the administrators.");
        disablePongCancelling = config.getBooleanElse("disable-pong-cancelling", false);
        launchFailOpen = config.getBooleanElse("launch-safety.fail-open", true);
        maxPingKnockback = config.getIntElse("cult.prediction.max-knockback-ping-ms", 1000);

        // owner-authored config semantics: strip e.g. the " (Velocity)" suffix from brands
        String brandRegex = config.getStringElse("cult.client-brand.strip-pattern", " \\(Velocity\\)$");
        if (!brandRegex.isEmpty()) {
            try {
                brandRemover = Pattern.compile(brandRegex);
            } catch (PatternSyntaxException e) {
                throw new RuntimeException("Failed to compile brand remover pattern", e);
            }
        } else {
            brandRemover = null;
        }
        int configuredUpdatePermissionTicks = config.getIntElse("update-permission-ticks", -1);
        updatePermissionTicks = configuredUpdatePermissionTicks <= 0 ? -1 : configuredUpdatePermissionTicks;

        verboseBedrockMovement = config.getBooleanElse("cult.diagnostics.bedrock-movement.enabled", false);
        verboseBedrockMovementLogCleanOffsets =
                config.getBooleanElse("cult.diagnostics.bedrock-movement.log-clean-offsets", false);
        verboseBedrockMovementMinOffset =
                nonNegativeElse(config, "cult.diagnostics.bedrock-movement.min-offset", 1.0E-7D);
        verboseBedrockMovementCooldownSeconds =
                nonNegativeElse(config, "cult.diagnostics.bedrock-movement.cooldown-seconds", 10.0D);
        bedrockMovementPositionFlagThreshold =
                nonNegativeElse(config, "cult.checks.bedrock-movement.position-flag-threshold", 0.001D);
        bedrockMovementPositionReconciliationStep =
                nonNegativeFiniteElse(config, "cult.checks.bedrock-movement.position-reconciliation-step", 0.0005D);
        bedrockMovementVelocityFlagThreshold =
                nonNegativeElse(config, "cult.checks.bedrock-movement.velocity-flag-threshold", 0.001D);
        bedrockMovementSetbacksEnabled = config.getBooleanElse("cult.checks.bedrock-movement.enable-setbacks", true);
    }

    /** {@code CULTAC_OBSERVE_ONLY} when it reads true/1/yes or false/0/no, else {@code observe-only}. */
    static boolean resolveObserveOnly(ConfigManager config, Function<String, String> env) {
        Boolean pinned = observeOnlyPin(env);
        return pinned != null ? pinned : config.getBooleanElse(OBSERVE_ONLY_KEY, false);
    }

    /** The mode {@code CULTAC_OBSERVE_ONLY} pins, or null when the config key decides. */
    public static @Nullable Boolean observeOnlyPin(Function<String, String> env) {
        String value = env.apply(OBSERVE_ONLY_ENV);
        if (value == null) return null;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> null;
        };
    }

    public static List<String> resolveCommandRoots(@Nullable ConfigManager config, Consumer<String> warn) {
        List<String> configured = config == null ? null : config.getStringListElse(CommandRoots.KEY, null);
        return CommandRoots.resolve(configured, warn);
    }

    public boolean isIgnoredClient(String brand) {
        for (Pattern pattern : ignoredClientPatterns) {
            if (pattern.matcher(brand).find()) return true;
        }
        return false;
    }

    public String simplifyBrand(String brand) {
        if (brandRemover == null) return brand;
        return brandRemover.matcher(brand).replaceFirst("");
    }
}
