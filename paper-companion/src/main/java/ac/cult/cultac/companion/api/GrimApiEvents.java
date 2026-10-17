package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandExecute;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Metrics;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reloaded;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Setback;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Verdict;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.bridge.CompanionMessageHandler;
import ac.cult.cultac.companion.bridge.CompanionSession;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.api.event.events.GrimReloadEvent;
import ac.grim.grimac.api.event.events.GrimTeleportEvent;
import ac.grim.grimac.api.event.events.GrimTransactionReceivedEvent;
import ac.grim.grimac.api.event.events.GrimTransactionSendEvent;
import ac.grim.grimac.api.event.events.GrimVehicleSetbackEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Feeds bridge messages into {@link CompanionGrimAPI} and fires the Grim events: GrimQuitEvent on the main thread (on
 * the player's netty thread when the player quits while GrimJoinEvent runs, right after it), the others on the player's
 * netty thread. A FlagEvent cancel reaches the proxy when the FLAG awaits a verdict; a CommandExecuteEvent cancel stops
 * the punishment command. The legacy Bukkit FlagEvent and CommandExecuteEvent fire from their bus channels at priority
 * 0, skipped once cancelled.
 *
 * <p>GrimJoinEvent fires once per Bukkit join, for its first READY session: it waits for that session's first USER_INFO
 * and the last chunk of its first CHECK_CATALOG, or one second after READY, whichever comes first; the session's
 * messages that follow wait for it. GrimQuitEvent fires on {@code PlayerQuitEvent}, only after a join, and never before
 * GrimJoinEvent has returned. A session that reopens while the player stays online re-attaches without either event.
 * The legacy Bukkit join and quit events fire with them; on the main thread they are queued for an async thread and
 * never awaited.
 */
final class GrimApiEvents implements CompanionMessageHandler {
    static final long JOIN_FALLBACK_TICKS = 20L;

    /** Delayed scheduling for the join gate's fallback. */
    interface Later {
        /** Runs the task after {@code ticks}; the returned action cancels it. */
        Runnable later(Runnable task, long ticks);
    }

    /** Runs a console command on the main thread; completes with whether it dispatched. */
    interface ConsoleCommands {
        CompletableFuture<Boolean> dispatch(String command);
    }

    /** Join readiness of one session: the input flags are written on its netty thread. */
    private static final class JoinGate {
        final CompanionUser user;
        final AtomicBoolean fired = new AtomicBoolean();
        volatile boolean userInfo;
        volatile boolean catalog;
        boolean requested;
        volatile Runnable cancelFallback;

        JoinGate(CompanionUser user) {
            this.user = user;
        }
    }

    private final CompanionGrimAPI api;
    private final GrimPlugin owner;
    private final CompanionBridge bridge;
    private final LegacyEvents legacy;
    private final LongSupplier clock;
    private final Later later;
    private final ConsoleCommands console;
    private final Map<CompanionSession, JoinGate> joins = new ConcurrentHashMap<>();
    private final FlagEvent.Channel flag;
    private final GrimJoinEvent.Channel join;
    private final GrimQuitEvent.Channel quit;
    private final GrimReloadEvent.Channel reload;
    private final GrimTeleportEvent.Channel teleport;
    private final GrimPlayerSetbackEvent.Channel setback;
    private final CommandExecuteEvent.Channel commandExecute;
    private final CompletePredictionEvent.Channel completePrediction;
    private final GrimTransactionSendEvent.Channel transactionSend;
    private final GrimTransactionReceivedEvent.Channel transactionReceived;
    private final GrimVehicleSetbackEvent.Channel vehicleSetback;
    private final FlagEvent.SupplierHandler legacyFlag = this::fireLegacyFlag;
    private boolean legacyFlagSubscribed;
    private final CommandExecuteEvent.SupplierHandler legacyCommandExecute = this::fireLegacyCommandExecute;
    private boolean legacyCommandExecuteSubscribed;
    private volatile boolean enabled = true;
    private int listenerBits = -1;
    private boolean warnedPrediction;
    private boolean warnedTransactions;
    private boolean warnedVehicleSetback;

    GrimApiEvents(
            CompanionGrimAPI api,
            GrimPlugin owner,
            CompanionBridge bridge,
            LegacyEvents legacy,
            LongSupplier clock,
            Later later,
            ConsoleCommands console) {
        this.api = api;
        this.owner = owner;
        this.bridge = bridge;
        this.legacy = legacy;
        this.clock = clock;
        this.later = later;
        this.console = console;
        EventBus bus = api.getEventBus();
        this.flag = bus.get(FlagEvent.class);
        this.join = bus.get(GrimJoinEvent.class);
        this.quit = bus.get(GrimQuitEvent.class);
        this.reload = bus.get(GrimReloadEvent.class);
        this.teleport = bus.get(GrimTeleportEvent.class);
        this.setback = bus.get(GrimPlayerSetbackEvent.class);
        this.commandExecute = bus.get(CommandExecuteEvent.class);
        this.completePrediction = bus.get(CompletePredictionEvent.class);
        this.transactionSend = bus.get(GrimTransactionSendEvent.class);
        this.transactionReceived = bus.get(GrimTransactionReceivedEvent.class);
        this.vehicleSetback = bus.get(GrimVehicleSetbackEvent.class);
    }

    void disable() {
        enabled = false;
        if (legacyFlagSubscribed) flag.unsubscribe(legacyFlag);
        legacyFlagSubscribed = false;
        if (legacyCommandExecuteSubscribed) commandExecute.unsubscribe(legacyCommandExecute);
        legacyCommandExecuteSubscribed = false;
    }

    @Override
    public void onReady(CompanionSession session) {
        if (!enabled) return;
        JoinGate gate = new JoinGate(api.open(session));
        joins.put(session, gate);
        gate.cancelFallback = later.later(() -> session.execute(() -> fireJoin(session, gate)), JOIN_FALLBACK_TICKS);
    }

    /** Main thread; the user stays registered until the player quits. */
    @Override
    public void onClosed(CompanionSession session) {
        JoinGate gate = joins.remove(session);
        if (gate == null) return;
        if (gate.cancelFallback != null) gate.cancelFallback.run();
        gate.user.detach(session);
    }

    /** Main thread, from {@code PlayerQuitEvent}; fires GrimQuitEvent when GrimJoinEvent fired for this join. */
    void playerQuit(Player player) {
        CompanionUser user = api.quit(player);
        if (user == null || !user.markQuit() || !enabled) return;
        fireQuit(user);
    }

    private void fireQuit(CompanionUser user) {
        quit.fire(user);
        legacy.quit(user);
    }

    @Override
    public void onMessage(CompanionSession session, BridgeMessage message) {
        if (!enabled) return;
        CompanionUser user = api.userOf(session);
        if (user == null) {
            if (message instanceof Flag received && received.awaitVerdict()) {
                session.send(new Verdict(received.flagId(), false));
            } else if (message instanceof CommandExecute received) {
                onCommandExecute(session, null, received);
            }
            return;
        }
        switch (message) {
            case UserInfo info -> {
                user.apply(info);
                joinInputArrived(session, true);
            }
            case Metrics metrics -> user.apply(metrics);
            case CheckCatalog catalog -> {
                user.apply(catalog);
                api.checkPermissions().register(catalog.checks());
                if (catalog.last()) joinInputArrived(session, false);
            }
            case AlertState state -> user.setAlertBits(state.bits());
            case Flag received -> onFlag(session, user, received);
            case CommandExecute received -> onCommandExecute(session, user, received);
            case Setback received -> {
                long now = clock.getAsLong();
                teleport.fire(user, received.teleportId(), now);
                setback.fire(user, received.teleportId(), received.x(), received.y(), received.z(), now);
            }
            case Reloaded received -> {
                reload.fire(received.success());
                legacy.reload(received.success());
            }
            default -> {}
        }
    }

    /** Netty thread. */
    private void joinInputArrived(CompanionSession session, boolean userInfo) {
        JoinGate gate = joins.get(session);
        if (gate == null || gate.requested) return;
        if (userInfo) gate.userInfo = true;
        else gate.catalog = true;
        if (!gate.userInfo || !gate.catalog) return;
        gate.requested = true;
        fireJoin(session, gate);
    }

    /** The session's netty thread; fires at most once per session and never after the session closed. */
    private void fireJoin(CompanionSession session, JoinGate gate) {
        if (!enabled || joins.get(session) != gate || !gate.fired.compareAndSet(false, true)) return;
        Runnable cancelFallback = gate.cancelFallback;
        if (cancelFallback != null) cancelFallback.run();
        if (!gate.user.beginJoin()) return;
        try {
            join.fire(gate.user);
            legacy.join(gate.user);
        } finally {
            if (!gate.user.finishJoin() && enabled) fireQuit(gate.user);
        }
    }

    /**
     * FLAG carries the violations the proxy check holds once the flag commits; listeners here see one less. A FLAG awaiting a verdict is answered at once on this thread and commits here only if not cancelled.
     */
    private void onFlag(CompanionSession session, CompanionUser user, Flag received) {
        CompanionCheck check = user.check(received.checkIdx());
        if (check == null) {
            if (received.awaitVerdict()) session.send(new Verdict(received.flagId(), false));
            return;
        }
        check.setViolations(received.violations() - 1);
        boolean cancelled = flag.fire(user, check, received.verbose());
        if (received.awaitVerdict()) session.send(new Verdict(received.flagId(), cancelled));
        if (!received.awaitVerdict() || !cancelled) check.commitFlag(received.violations(), clock.getAsLong());
    }

    private boolean fireLegacyFlag(GrimUser user, AbstractCheck check, Supplier<String> verbose, boolean cancelled) {
        return legacy.flag(user, check, verbose.get()) || cancelled;
    }

    private boolean fireLegacyCommandExecute(
            GrimUser user, AbstractCheck check, Supplier<String> verbose, String command, boolean cancelled) {
        return legacy.commandExecute(user, check, verbose.get(), command) || cancelled;
    }

    /** Fires CommandExecuteEvent, then runs the command on the console unless a listener cancelled it. */
    private void onCommandExecute(CompanionSession session, @Nullable CompanionUser user, CommandExecute received) {
        CompanionCheck check = user == null ? null : user.check(received.checkIdx());
        if (check != null) {
            double held = check.getViolations();
            check.setViolations(received.violations());
            boolean cancelled = commandExecute.fire(user, check, received.verbose(), received.command());
            check.setViolations(Math.max(held, received.violations() + 1));
            if (cancelled) {
                session.send(new Result(received.requestId(), false));
                return;
            }
        } else {
            api.warnings()
                    .warn(
                            "CommandExecuteEvent.unknownCheck",
                            "A punishment command arrived for a player or check this backend does not know yet; it"
                                    + " runs without firing CommandExecuteEvent");
        }
        console.dispatch(received.command())
                .whenComplete((dispatched, failure) -> session.send(
                        new Result(received.requestId(), failure == null && Boolean.TRUE.equals(dispatched))));
    }

    /** {@link ListenerKind} bits of the Grim listeners registered on this backend, new bus and legacy Bukkit. */
    int listenerBits() {
        int bits = 0;
        if (!flag.isEmpty() || LegacyEvents.hasFlag()) bits |= ListenerKind.FLAG.flag();
        if (!commandExecute.isEmpty() || LegacyEvents.hasCommandExecute()) bits |= ListenerKind.COMMAND_EXECUTE.flag();
        if (!setback.isEmpty() || !teleport.isEmpty()) bits |= ListenerKind.SETBACK.flag();
        if (!reload.isEmpty() || LegacyEvents.hasReload()) bits |= ListenerKind.RELOAD.flag();
        return bits;
    }

    /** Reports changed listener bits to the bridge; allocation-free when nothing changed. Main thread only. */
    void refreshListeners() {
        if (!enabled) return;
        subscribeLegacyFlag(LegacyEvents.hasFlag());
        subscribeLegacyCommandExecute(LegacyEvents.hasCommandExecute());
        int bits = listenerBits();
        if (!warnedPrediction && (!completePrediction.isEmpty() || LegacyEvents.hasCompletePrediction())) {
            warnedPrediction = true;
            warnNeverFired("CompletePredictionEvent", "GrimEvent/GrimCheckEvent");
        }
        if (!warnedTransactions && (!transactionSend.isEmpty() || !transactionReceived.isEmpty())) {
            warnedTransactions = true;
            warnNeverFired("GrimTransactionSendEvent/GrimTransactionReceivedEvent", "GrimEvent");
        }
        if (!warnedVehicleSetback && !vehicleSetback.isEmpty()) {
            warnedVehicleSetback = true;
            warnNeverFired("GrimVehicleSetbackEvent", "GrimEvent/GrimSetbackEvent");
        }
        if (bits == listenerBits) return;
        listenerBits = bits;
        bridge.setListenerBits(bits);
    }

    /** Subscribes the legacy FlagEvent only while a Bukkit listener exists, so it never counts as a bus listener alone. */
    private void subscribeLegacyFlag(boolean listened) {
        if (listened == legacyFlagSubscribed) return;
        legacyFlagSubscribed = listened;
        if (listened) flag.onFlagSupplier(owner, legacyFlag, 0, false);
        else flag.unsubscribe(legacyFlag);
    }

    /** Subscribes the legacy CommandExecuteEvent only while a Bukkit listener exists, as the legacy FlagEvent. */
    private void subscribeLegacyCommandExecute(boolean listened) {
        if (listened == legacyCommandExecuteSubscribed) return;
        legacyCommandExecuteSubscribed = listened;
        if (listened) commandExecute.onCommandExecuteSupplier(owner, legacyCommandExecute, 0, false);
        else commandExecute.unsubscribe(legacyCommandExecute);
    }

    private void warnNeverFired(String event, String parents) {
        api.warnings()
                .warn(
                        event,
                        "A plugin listens for " + event + " (directly or through " + parents + "), which the CultAC"
                                + " proxy companion never fires; use a proxy plugin");
    }
}
