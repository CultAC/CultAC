package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.manager.AlertManagerImpl.Recipients;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public class PunishmentManager implements ConfigReloadable {
    private static final CommandExecuteEvent.Channel COMMAND_CHANNEL =
            CultAPI.INSTANCE.getEventBus().get(CommandExecuteEvent.class);
    private static final Set<String> BUILTIN_ACTIONS = Set.of("[webhook]", "[log]", "[proxy]", "[alert]");
    private final CultPlayer player;
    private final List<PunishGroup> groups = new ArrayList<>();
    private String experimentalSymbol = "*";
    private String alertString;
    private boolean testMode;
    private String proxyAlertString = "";

    public PunishmentManager(CultPlayer player) {
        this.player = player;
    }

    @Override
    public void reload(ConfigManager config) {
        List<String> punish = config.getStringListElse("Punishments", new ArrayList<>());
        experimentalSymbol = config.getStringElse("experimental-symbol", "*");

        alertString = config.getStringElse("alerts-format", EnglishDefaults.string("messages", "alerts-format"));

        testMode = config.getBooleanElse("test-mode", false);

        proxyAlertString =
                config.getStringElse("alerts-format-proxy", EnglishDefaults.string("messages", "alerts-format-proxy"));

        PunishmentRules rules = PunishmentRules.parse(punish);
        groups.clear();

        // To support reloading
        for (AbstractCheck check : player.getChecks()) {
            check.setEnabled(false);
        }

        AbstractCheck[] known = player.getChecks().toArray(new AbstractCheck[0]);
        String[] names = new String[known.length];
        String[] alternativeNames = new String[known.length];
        for (int i = 0; i < known.length; i++) {
            names[i] = lowerCase(known[i].getCheckName());
            alternativeNames[i] = lowerCase(known[i].getAlternativeName());
        }
        for (PunishmentRules.Group rule : rules.groups()) {
            List<AbstractCheck> held = new ArrayList<>();
            for (int i = 0; i < known.length; i++) {
                if (rule.holds(names[i], alternativeNames[i])) held.add(known[i]);
            }
            for (AbstractCheck check : held) check.setEnabled(true);

            List<ParsedCommand> parsed = new ArrayList<>();
            for (PunishmentRules.Command command : rule.commands()) {
                parsed.add(new ParsedCommand(
                        command.threshold(),
                        command.interval(),
                        command.command(),
                        alertFormat(config, command.command())));
            }
            groups.add(new PunishGroup(held, parsed, rule.removeViolationsAfter() * 1000));
        }
        for (int i = 0; i < known.length; i++) {
            if (PunishmentRules.holds(rules.unreadGroupSelectors(), names[i], alternativeNames[i])) {
                known[i].setEnabled(true);
            }
        }
        if (rules.failure() != null) {
            LogUtil.error("Error while loading punishments.yml! This is likely your fault!", rules.failure());
        }
    }

    /** What {@code command} alerts with: {@code alerts-format}, or the message an {@code [alert:<key>]} names. */
    private @Nullable String alertFormat(ConfigManager config, String command) {
        if (command.equals("[alert]")) return alertString;
        String key = PunishmentRules.alertKey(command);
        if (key == null) return null;
        String named = namedAlertFormat(config, key);
        return named != null ? named : alertString;
    }

    /** The message {@code key} names, or null when it names none or a blank one. */
    static @Nullable String namedAlertFormat(ConfigManager config, String key) {
        if (key.isEmpty()) return null;
        String format;
        try {
            format = config.getStringElse(key, EnglishDefaults.stringOrNull("messages", key));
        } catch (RuntimeException notText) {
            return null;
        }
        return format == null || format.isBlank() ? null : format;
    }

    /**
     * One warning per group and key for each {@code [alert:<key>]} whose key names no message; such an alert sends
     * {@code alerts-format}.
     */
    public static List<String> unknownAlertFormats(ConfigManager config) {
        List<String> warnings = new ArrayList<>();
        List<String> punish = config.getStringListElse("Punishments", new ArrayList<>());
        for (PunishmentRules.Group group : PunishmentRules.parse(punish).groups()) {
            Set<String> warned = new HashSet<>();
            for (PunishmentRules.Command command : group.commands()) {
                String key = PunishmentRules.alertKey(command.command());
                if (key == null || namedAlertFormat(config, key) != null || !warned.add(key)) continue;
                warnings.add("punishments.yml: the group with checks " + group.selectors() + " runs [alert:" + key
                        + "], but no message is named '" + key + "'; it sends alerts-format instead");
            }
        }
        return warnings;
    }

    private static @Nullable String lowerCase(@Nullable String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /** {@code original} with its placeholders filled; {@code verbose} is rendered only for a %verbose% in it. */
    private String replaceAlertPlaceholders(ParsedCommand command, int vl, Check check, Supplier<String> verbose) {
        String original = command.alertFormat != null
                ? command.alertFormat
                : command.command.replace("[alert]", alertString).replace("[proxy]", proxyAlertString);
        String replaced = MessageUtil.replacePlaceholders(
                player,
                original.replace("%check_name%", check.getDisplayName())
                        .replace("%experimental%", check.isExperimental() ? experimentalSymbol : "")
                        .replace("%vl%", Integer.toString(vl))
                        .replace("%description%", check.getDescription())
                        .replace("%stable_key%", check.getStableKey()));
        if (!replaced.contains("%verbose%")) return replaced;
        return replaced.replace("%verbose%", MessageUtil.miniMessageSafe(safeGet(verbose)));
    }

    public boolean handleAlert(CultPlayer player, String verbose, Check check) {
        String value = verbose == null ? "" : verbose;
        return handleAlert(player, () -> value, check);
    }

    public boolean handleAlert(CultPlayer player, Supplier<String> verbose, Check check) {
        return handleAlert(player, verbose, verbose, check);
    }

    /**
     * Runs the commands a flag of {@code check} makes due. Alerts, webhooks, proxy forwards and console commands show
     * {@code verbose}; a [log] row stores {@code logVerbose}. Each is rendered only for a command that uses it, since
     * verbose that shows a Cult Log identifier builds the log when it is rendered.
     */
    public boolean handleAlert(CultPlayer player, Supplier<String> verbose, Supplier<String> logVerbose, Check check) {
        boolean sentDebug = false;

        // Check commands
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) {
                final int vl = getViolations(group, check);
                final int violationCount = group.violations.size();
                for (ParsedCommand command : group.commands) {
                    @Nullable Recipients verboseListeners = null;
                    // Verbose that prints all flags: /cult verbose subscribers get EVERY flag, not just thresholded
                    // alerts. The verbose string is only rendered (safeGet) when there are listeners, so the flag
                    // path stays lazy when nobody is subscribed.
                    if (command.alertFormat != null
                            && CultAPI.INSTANCE.getAlertManager().hasVerboseListeners(player)) {
                        sentDebug = true;
                        try {
                            String listenerCmd = replaceAlertPlaceholders(command, vl, check, verbose);
                            verboseListeners = CultAPI.INSTANCE
                                    .getAlertManager()
                                    .sendVerbose(
                                            player,
                                            CheckNameLink.link(MessageUtil.miniMessage(listenerCmd), check),
                                            null);
                        } catch (RuntimeException e) {
                            logDeliveryFailure(command, check, e);
                        }
                    }
                    if (violationCount >= command.threshold) {
                        boolean shouldRun = command.interval == 0
                                ? command.executeCount == 0
                                : violationCount >= command.nextBoundary;
                        if (shouldRun) {
                            Supplier<String> text = command.command.equals("[log]") ? logVerbose : verbose;
                            Supplier<String> shown = () -> safeGet(text);
                            String cmd = replaceAlertPlaceholders(command, vl, check, shown);
                            // Only console commands can reach a backend's CommandExecuteEvent listeners;
                            // the built-in actions below are decided by this event alone.
                            boolean withheld =
                                    CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                                            && !BUILTIN_ACTIONS.contains(command.action());
                            boolean canceled = withheld
                                    || COMMAND_CHANNEL.fire(
                                            ac.cult.cultac.runtime.RuntimeEnvironment.outwardUser(player),
                                            ac.cult.cultac.runtime.RuntimeEnvironment.outwardCheck(check),
                                            shown,
                                            cmd);
                            if (command.interval == 0) {
                                command.executeCount++;
                            } else {
                                advanceBoundary(command, violationCount);
                            }
                            if (canceled) continue;

                            try {
                                switch (command.action()) {
                                    case "[webhook]" ->
                                        CultAPI.INSTANCE
                                                .getDiscordManager()
                                                .sendAlert(player, safeGet(verbose), check.getDisplayName(), vl);
                                    case "[log]" -> {
                                        // Binary flags already stored a row; avoid an extra legacy text row.
                                        if (!check.isLastFlagStoredBinaryVerbose()) {
                                            String verboseWithoutGl =
                                                    safeGet(logVerbose).replaceAll(" /gl .*", "");
                                            CultAPI.INSTANCE
                                                    .getDataStoreLifecycle()
                                                    .liveWriteHooks()
                                                    .recordFlagFromCheck(player, check, vl, verboseWithoutGl);
                                        }
                                    }
                                    case "[proxy]" ->
                                        CultAPI.INSTANCE.getAlertManager().forwardAlert(cmd);
                                    case "[alert]" -> {
                                        sentDebug = true;
                                        Component message = CheckNameLink.link(MessageUtil.miniMessage(cmd), check);
                                        if (testMode) { // secret test mode
                                            if (verboseListeners == null
                                                    || verboseListeners
                                                            .players()
                                                            .contains(player.platformPlayer)) {
                                                player.sendMessage(message);
                                            }
                                        } else {
                                            CultAPI.INSTANCE
                                                    .getAlertManager()
                                                    .sendAlert(player, message, verboseListeners);
                                        }
                                    }
                                    default -> {
                                        double violations = check.getViolations();
                                        // Only where the platform hands it on, such as to a backend plugin.
                                        String renderedVerbose = CultAPI.INSTANCE
                                                        .getPlatformServer()
                                                        .dispatchesVerbose(cmd, player.platformPlayer)
                                                ? safeGet(verbose)
                                                : "";
                                        CultAPI.INSTANCE
                                                .getScheduler()
                                                .getGlobalRegionScheduler()
                                                .run(
                                                        CultAPI.INSTANCE.getGrimPlugin(),
                                                        () -> CultAPI.INSTANCE
                                                                .getPlatformServer()
                                                                .dispatchCommand(
                                                                        CultAPI.INSTANCE
                                                                                .getPlatformServer()
                                                                                .getConsoleSender(),
                                                                        cmd,
                                                                        player.platformPlayer,
                                                                        check,
                                                                        violations,
                                                                        renderedVerbose));
                                    }
                                }
                            } catch (RuntimeException e) {
                                logDeliveryFailure(command, check, e);
                            }
                        }

                        if (command.interval > 0) command.executeCount++;
                    } else {
                        // Interval commands re-arm after the active rolling count
                        // cools below their threshold.
                        command.nextBoundary = command.threshold;
                    }
                }
            }
        }

        return sentDebug;
    }

    /**
     * Whether committing {@code pending} more flags of {@code check} could make {@link #handleAlert} render a flag's
     * shown verbose: a verbose listener hears every flag an alerting group takes, and a command other than [log]
     * renders it once the group's violations reach it. Leans to true, since violations may expire before the flags
     * commit.
     */
    public boolean mayRenderVerbose(Check check, int pending) {
        boolean verboseListeners = CultAPI.INSTANCE.getAlertManager().hasVerboseListeners(player);
        for (PunishGroup group : groups) {
            if (!group.checks.contains(check)) continue;
            int violationCount = group.violations.size() + pending;
            for (ParsedCommand command : group.commands) {
                if (command.command.equals("[log]")) continue;
                if (verboseListeners && command.alertFormat != null) return true;
                if (violationCount >= command.threshold
                        && (command.interval == 0 ? command.executeCount == 0 : violationCount >= command.nextBoundary))
                    return true;
            }
        }
        return false;
    }

    private static void logDeliveryFailure(ParsedCommand command, Check check, RuntimeException e) {
        LogUtil.error("Failed to run " + command.command + " for " + check.getCheckName(), e);
    }

    private static String safeGet(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void advanceBoundary(ParsedCommand command, int violationCount) {
        command.nextBoundary += ((violationCount - command.nextBoundary) / command.interval + 1) * command.interval;
    }

    public void handleViolation(Check check) {
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) {
                long currentTime = System.currentTimeMillis();

                group.violations.put(currentTime, check);
                // Remove violations older than the defined time in the config
                group.violations
                        .long2ObjectEntrySet()
                        .removeIf(time -> currentTime - time.getLongKey() > group.removeViolationsAfter);
            }
        }
    }

    private int getViolations(PunishGroup group, Check check) {
        int vl = 0;
        for (Check value : group.violations.values()) {
            if (value == check) vl++;
        }
        return vl;
    }
}

@RequiredArgsConstructor
class PunishGroup {
    public final List<AbstractCheck> checks;
    public final List<ParsedCommand> commands;
    public final Long2ObjectMap<Check> violations = new Long2ObjectOpenHashMap<>();
    public final int removeViolationsAfter; // time to remove violations after in milliseconds
}

class ParsedCommand {
    public final int threshold;
    public final int interval;
    public final String command;
    // The text an [alert] or [alert:<key>] sends; null for every other command.
    public final @Nullable String alertFormat;
    // For M=0, a non-zero count keeps the command from running again in this loaded state.
    public int executeCount;
    // For M>0, the next active violation count that should run this command.
    public int nextBoundary;

    public ParsedCommand(int threshold, int interval, String command, @Nullable String alertFormat) {
        this.threshold = threshold;
        this.interval = interval;
        this.command = command;
        this.alertFormat = alertFormat;
        this.nextBoundary = threshold;
    }

    /** The built-in action or console command this runs; every alert runs as [alert]. */
    String action() {
        return alertFormat != null ? "[alert]" : command;
    }
}
