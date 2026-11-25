package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckNames;
import ac.cult.cultac.manager.AlertManagerImpl.Recipients;
import ac.cult.cultac.manager.PunishmentLedger.GroupState;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public class PunishmentManager implements ConfigReloadable {
    private static final CommandExecuteEvent.Channel COMMAND_CHANNEL =
            CultAPI.INSTANCE.getEventBus().get(CommandExecuteEvent.class);
    static final Set<String> BUILTIN_ACTIONS = Set.of("[webhook]", "[log]", "[proxy]", "[alert]");
    private final CultPlayer player;
    private final LongSupplier clock;
    private final PunishmentLedger ledger;
    private final List<PunishGroup> groups = new ArrayList<>();
    private volatile @Nullable UUID ledgerId;
    // The state the groups count in, as the ledger last handed it out.
    private List<GroupState> states = List.of();
    private volatile boolean released;
    private String experimentalSymbol = "*";
    private String alertString;
    private boolean testMode;
    private String proxyAlertString = "";

    public PunishmentManager(CultPlayer player) {
        this(player, System::currentTimeMillis, PunishmentLedger.INSTANCE);
    }

    /**
     * @param clock the wall clock in milliseconds that ages the groups' flags
     * @param ledger the state the player's connections share
     */
    PunishmentManager(CultPlayer player, LongSupplier clock, PunishmentLedger ledger) {
        this.player = player;
        this.clock = clock;
        this.ledger = ledger;
    }

    /** Drops the punishment state kept for players who left. */
    public static void forgetPlayersWhoLeft() {
        PunishmentLedger.INSTANCE.clear();
    }

    /**
     * The state the groups count in: the first load of a connection joins the player's shared state, a later load
     * starts it over.
     */
    private List<GroupState> counted(List<PunishmentRules.Group> rules, List<GroupState> fresh) {
        UUID id = player.getUniqueId();
        if (id == null || released) return fresh;
        if (ledgerId == null) {
            ledgerId = id;
            return states = ledger.attach(id, this, rules, fresh, clock.getAsLong());
        }
        return states = ledger.restart(id, this, states, rules, fresh);
    }

    /** Ends this connection's share of the player's punishment state. */
    public void release() {
        if (released) return;
        released = true;
        if (ledgerId != null) ledger.detach(ledgerId, this, clock.getAsLong());
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
        // The groups name checks as the name version of punishments.yml has them.
        int nameVersion = CheckNames.version(config, CheckNames.PUNISHMENTS_KEY);
        for (int i = 0; i < known.length; i++) {
            names[i] = lowerCase(known[i] instanceof Check check ? check.nameIn(nameVersion) : known[i].getCheckName());
            alternativeNames[i] = lowerCase(known[i].getAlternativeName());
        }
        List<GroupState> fresh = new ArrayList<>();
        for (PunishmentRules.Group rule : rules.groups()) {
            List<ParsedCommand> parsed = new ArrayList<>();
            for (PunishmentRules.Command command : rule.commands()) {
                parsed.add(new ParsedCommand(
                        command.threshold(),
                        command.interval(),
                        command.command(),
                        alertFormat(config, command.command())));
            }
            fresh.add(new GroupState(parsed, new ViolationWindow(), rule.removeViolationsAfter() * 1000));
        }
        List<GroupState> states = counted(rules.groups(), fresh);
        for (int g = 0; g < states.size(); g++) {
            PunishmentRules.Group rule = rules.groups().get(g);
            List<AbstractCheck> held = new ArrayList<>();
            for (int i = 0; i < known.length; i++) {
                if (rule.holds(names[i], alternativeNames[i])) held.add(known[i]);
            }
            for (AbstractCheck check : held) check.setEnabled(true);
            groups.add(new PunishGroup(new HashSet<>(held), states.get(g)));
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

    /**
     * {@code original} with its placeholders filled, client values and {@code verbose} as text for {@link
     * MessageUtil#miniMessage} when {@code asText}; {@code verbose} is rendered only for a %verbose% in it.
     */
    private String replaceAlertPlaceholders(
            ParsedCommand command, int vl, Check check, Supplier<String> verbose, boolean asText) {
        String original = command.alertFormat != null
                ? command.alertFormat
                : command.command.replace("[alert]", alertString).replace("[proxy]", proxyAlertString);
        String filled = original.replace("%check_name%", check.getDisplayName())
                .replace("%experimental%", check.isExperimental() ? experimentalSymbol : "")
                .replace("%vl%", Integer.toString(vl))
                .replace("%description%", check.getDescription())
                .replace("%stable_key%", check.getStableKey());
        String replaced = asText
                ? MessageUtil.replacePlaceholdersForMiniMessage(player, filled)
                : MessageUtil.replacePlaceholders(player, filled);
        if (!replaced.contains("%verbose%")) return replaced;
        String text = safeGet(verbose);
        return replaced.replace("%verbose%", asText ? MessageUtil.plainText(text) : MessageUtil.miniMessageSafe(text));
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
                final int vl;
                final int violationCount;
                synchronized (group.violations) {
                    vl = getViolations(group, check);
                    violationCount = group.violations.size();
                }
                for (ParsedCommand command : group.commands) {
                    @Nullable Recipients verboseListeners = null;
                    // Verbose that prints all flags: /cult verbose subscribers get EVERY flag, not just thresholded
                    // alerts. The verbose string is only rendered (safeGet) when there are listeners, so the flag
                    // path stays lazy when nobody is subscribed.
                    if (command.alertFormat != null
                            && CultAPI.INSTANCE.getAlertManager().hasVerboseListeners(player)) {
                        sentDebug = true;
                        try {
                            String listenerCmd = replaceAlertPlaceholders(command, vl, check, verbose, true);
                            verboseListeners = CultAPI.INSTANCE
                                    .getAlertManager()
                                    .sendVerbose(
                                            player,
                                            CheckNameLink.link(MessageUtil.miniMessage(listenerCmd), check),
                                            null);
                        } catch (RuntimeException | LinkageError e) {
                            logDeliveryFailure(command, check, e);
                        }
                    }
                    if (due(group, command, violationCount)) {
                        Supplier<String> text = command.command.equals("[log]") ? logVerbose : verbose;
                        Supplier<String> shown = () -> safeGet(text);
                        if (withheld(player, command)) {
                            if (command.interval == 0) holdBack(group, command);
                            continue;
                        }
                        if (command.interval == 0 && !claim(group, command)) continue;
                        boolean handedOver = command.interval > 0;
                        boolean builtin = BUILTIN_ACTIONS.contains(command.action());
                        // Only console commands can reach a backend's CommandExecuteEvent listeners;
                        // the built-in actions below are decided by this event alone.
                        // The configured line is for the event's listeners and a console command.
                        String cmd = null;
                        boolean canceled = false;
                        if (!builtin || !COMMAND_CHANNEL.isEmpty()) {
                            try {
                                cmd = replaceAlertPlaceholders(command, vl, check, shown, false);
                                canceled = COMMAND_CHANNEL.fire(
                                        RuntimeEnvironment.outwardUser(player),
                                        RuntimeEnvironment.outwardCheck(check),
                                        shown,
                                        cmd);
                            } catch (RuntimeException | LinkageError e) {
                                if (!handedOver) unclaim(group, command);
                                logDeliveryFailure(command, check, e);
                                continue;
                            }
                        }
                        if (!handedOver && (canceled || builtin)) {
                            handedOver = true;
                            settle(group, command, null, true);
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
                                // Every version of the receiving server reads the line as MiniMessage.
                                case "[proxy]" ->
                                    CultAPI.INSTANCE
                                            .getAlertManager()
                                            .forwardAlert(MessageUtil.relayed(MessageUtil.miniMessage(
                                                    replaceAlertPlaceholders(command, vl, check, shown, true))));
                                case "[alert]" -> {
                                    sentDebug = true;
                                    Component message = CheckNameLink.link(
                                            MessageUtil.miniMessage(
                                                    replaceAlertPlaceholders(command, vl, check, shown, true)),
                                            check);
                                    if (testMode) { // secret test mode
                                        if (verboseListeners == null
                                                || verboseListeners.players().contains(player.platformPlayer)) {
                                            player.sendMessage(message);
                                        }
                                    } else {
                                        CultAPI.INSTANCE.getAlertManager().sendAlert(player, message, verboseListeners);
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
                                    handedOver = true;
                                    dispatch(
                                            group,
                                            command,
                                            new Delivery(cmd, check.getStableKey(), violations, renderedVerbose),
                                            check);
                                }
                            }
                        } catch (RuntimeException | LinkageError e) {
                            if (!handedOver) unclaim(group, command);
                            logDeliveryFailure(command, check, e);
                        }
                    }
                }
            }
        }

        return sentDebug;
    }

    /**
     * Runs the one-time console commands that wait for a server able to run them, such as a backend whose companion
     * was not ready yet, while the flags in the window still reach them. On the player's packet owner.
     */
    public void deliverWaiting() {
        long now = clock.getAsLong();
        for (PunishGroup group : groups) {
            for (ParsedCommand command : group.commands) {
                Delivery waiting;
                Check check;
                synchronized (group.violations) {
                    waiting = command.waiting;
                    if (waiting == null || command.claimed || command.executeCount > 0) continue;
                    if (withheld(player, command)) {
                        command.withheldThrough = group.violations.added();
                        command.waiting = null;
                        continue;
                    }
                    group.violations.expire(now, group.removeViolationsAfter);
                    check = heldCheck(group, waiting.checkKey());
                    if (check == null || group.violations.countAfter(command.withheldThrough) < command.threshold) {
                        command.waiting = null;
                        continue;
                    }
                    command.claimed = true;
                }
                dispatch(group, command, waiting, check);
            }
        }
    }

    private static @Nullable Check heldCheck(PunishGroup group, String checkKey) {
        for (AbstractCheck held : group.checks) {
            if (held instanceof Check check && check.getStableKey().equals(checkKey)) return check;
        }
        return null;
    }

    /** Whether punishments.yml commands other than the built-in actions are withheld for {@code player}. */
    private static boolean withheld(CultPlayer player, ParsedCommand command) {
        return (player.isObserveOnly() || player.isPunishmentsPaused()) && !BUILTIN_ACTIONS.contains(command.action());
    }

    /**
     * Whether {@code command} runs for the flag that brought the group to {@code violationCount}. A recurring command
     * moves to its next boundary here; a one-time command counts only the flags after its window mark.
     */
    private static boolean due(PunishGroup group, ParsedCommand command, int violationCount) {
        synchronized (group.violations) {
            if (command.interval == 0) {
                return command.executeCount == 0
                        && !command.claimed
                        && group.violations.countAfter(command.withheldThrough) >= command.threshold;
            }
            if (violationCount < command.threshold) {
                // Interval commands re-arm after the active rolling count
                // cools below their threshold.
                command.nextBoundary = command.threshold;
                return false;
            }
            command.executeCount++;
            if (violationCount < command.nextBoundary) return false;
            advanceBoundary(command, violationCount);
            return true;
        }
    }

    /** Takes a due one-time command for this run; false when another run already took it. */
    private static boolean claim(PunishGroup group, ParsedCommand command) {
        synchronized (group.violations) {
            if (command.executeCount > 0 || command.claimed) return false;
            command.claimed = true;
            return true;
        }
    }

    /** Gives back a one-time command taken for a run that never reached the platform. */
    private static void unclaim(PunishGroup group, ParsedCommand command) {
        synchronized (group.violations) {
            command.claimed = false;
        }
    }

    /** A withheld one-time command stays unused; the flags that made it due no longer count toward it. */
    private static void holdBack(PunishGroup group, ParsedCommand command) {
        synchronized (group.violations) {
            command.withheldThrough = group.violations.added();
            command.waiting = null;
        }
    }

    /** Hands {@code delivery} to the platform on the thread its commands need. */
    private void dispatch(PunishGroup group, ParsedCommand command, Delivery delivery, Check check) {
        try {
            CultAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(CultAPI.INSTANCE.getGrimPlugin(), () -> {
                boolean delivered = true;
                try {
                    PlatformServer server = CultAPI.INSTANCE.getPlatformServer();
                    delivered = server.dispatchCommand(
                            server.getConsoleSender(),
                            delivery.command(),
                            player.platformPlayer,
                            check,
                            delivery.violations(),
                            delivery.verbose());
                } catch (RuntimeException e) {
                    logDeliveryFailure(command, check, e);
                }
                settle(group, command, delivery, delivered);
            });
        } catch (RuntimeException e) {
            settle(group, command, delivery, true);
            logDeliveryFailure(command, check, e);
        }
    }

    /**
     * A one-time command is used once it was handed to something that runs it, and waits for the next flag or a
     * ready server otherwise.
     */
    private void settle(PunishGroup group, ParsedCommand command, @Nullable Delivery delivery, boolean delivered) {
        if (command.interval > 0) {
            if (!delivered) {
                LogUtil.warn("Punishment command did not run: no server can run it now: " + delivery.command());
            }
            return;
        }
        boolean waitStarted;
        synchronized (group.violations) {
            command.claimed = false;
            if (delivered) {
                command.executeCount++;
                command.usedAt = clock.getAsLong();
                command.waiting = null;
                return;
            }
            waitStarted = command.waiting == null;
            command.waiting = delivery;
        }
        if (waitStarted) {
            LogUtil.warn("Punishment command waits for a server that can run it: " + delivery.command());
        }
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
            synchronized (group.violations) {
                int violationCount = group.violations.size() + pending;
                for (ParsedCommand command : group.commands) {
                    if (command.command.equals("[log]")) continue;
                    if (verboseListeners && command.alertFormat != null) return true;
                    if (command.interval == 0
                            ? command.executeCount == 0
                                    && !command.claimed
                                    && group.violations.countAfter(command.withheldThrough) + pending
                                            >= command.threshold
                            : violationCount >= command.threshold && violationCount >= command.nextBoundary)
                        return true;
                }
            }
        }
        return false;
    }

    private static void logDeliveryFailure(ParsedCommand command, Check check, Throwable e) {
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
                long currentTime = clock.getAsLong();
                synchronized (group.violations) {
                    group.violations.add(currentTime, check.getStableKey());
                    group.violations.expire(currentTime, group.removeViolationsAfter);
                }
            }
        }
    }

    private int getViolations(PunishGroup group, Check check) {
        return group.violations.count(check.getStableKey());
    }
}

class PunishGroup {
    public final Set<AbstractCheck> checks;
    public final List<ParsedCommand> commands;
    public final ViolationWindow violations;
    public final int removeViolationsAfter; // time to remove violations after in milliseconds

    PunishGroup(Set<AbstractCheck> checks, GroupState state) {
        this.checks = checks;
        this.commands = state.commands();
        this.violations = state.violations();
        this.removeViolationsAfter = state.removeViolationsAfter();
    }
}

class ParsedCommand {
    public final int threshold;
    public final int interval;
    public final String command;
    // The text an [alert] or [alert:<key>] sends; null for every other command.
    public final @Nullable String alertFormat;
    // For M=0, a non-zero count keeps the command from running again in this loaded state.
    public int executeCount;
    // For M=0, when the command was used.
    public long usedAt;
    // For M=0, a run of this command is on its way to the platform.
    public boolean claimed;
    // For M=0, only the flags added after this window mark count toward it.
    public long withheldThrough;
    // For M=0, the run that waits for a server able to run it.
    public @Nullable Delivery waiting;
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

/** A console command as rendered for a flag of the check with {@code checkKey}. */
record Delivery(String command, String checkKey, double violations, String verbose) {}
