package ac.cult.cultac.platform.velocity.bridge.verdict;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Verdict;
import ac.cult.cultac.bridge.protocol.ListenerKind;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Lets backend FlagEvent listeners cancel proxy flags. A player's flags are deferred to their backend while the
 * session is READY with {@link ServerCapability#FLAG_VERDICT} and {@link ServerCapability#GRIM_API} and the backend
 * reports a {@link ListenerKind#FLAG} listener; that arbiter then owns FLAG for the player, so the API mirror's
 * notify-only FLAG stays silent.
 *
 * <p>An unanswered flag commits after {@link #TIMEOUT_MILLIS}. After {@link #BREAKER_TIMEOUTS} consecutive timeouts in
 * one session, that player's flags commit at once until their next session becomes READY.
 */
public final class VerdictService implements BridgeMessageListener {
    public static final long TIMEOUT_MILLIS = 100;
    public static final int BREAKER_TIMEOUTS = 5;

    private final Function<UUID, @Nullable VerdictPlayer> players;
    private final ToIntBiFunction<BridgeSession, AbstractCheck> checkIndex;
    private final Logger logger;
    private final Map<UUID, SessionArbiter> arbiters = new ConcurrentHashMap<>();
    private final Map<UUID, BridgeSession> readySessions = new ConcurrentHashMap<>();
    private final Set<String> trippedServers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    VerdictService(
            Function<UUID, @Nullable VerdictPlayer> players,
            ToIntBiFunction<BridgeSession, AbstractCheck> checkIndex,
            Logger logger) {
        this.players = players;
        this.checkIndex = checkIndex;
        this.logger = logger;
    }

    /**
     * @param checkIndex the CHECK_CATALOG index of a check on a session, or -1 when the backend has no entry for it
     */
    public static VerdictService create(
            Function<UUID, @Nullable CultPlayer> players,
            ToIntBiFunction<BridgeSession, AbstractCheck> checkIndex,
            Logger logger) {
        return new VerdictService(id -> CultVerdictPlayer.of(players.apply(id)), checkIndex, logger);
    }

    @Override
    public void onReady(BridgeSession session) {
        if (closed) return;
        readySessions.put(session.playerId(), session);
        if (!session.has(ServerCapability.FLAG_VERDICT) || !session.has(ServerCapability.GRIM_API)) return;
        VerdictPlayer player = players.apply(session.playerId());
        if (player == null) return;
        SessionArbiter arbiter = new SessionArbiter(session, player, this);
        SessionArbiter previous = arbiters.put(session.playerId(), arbiter);
        if (previous != null) previous.close();
        arbiter.refresh(shouldInstall(session));
    }

    @Override
    public void onMessage(BridgeSession session, BridgeMessage message) {
        SessionArbiter arbiter = arbiters.get(session.playerId());
        if (arbiter == null || arbiter.session != session) return;
        switch (message) {
            case Verdict verdict -> arbiter.verdict(verdict.flagId(), verdict.cancelled());
            case Listeners ignored -> arbiter.refresh(shouldInstall(session));
            default -> {}
        }
    }

    @Override
    public void onClosed(BridgeSession session) {
        readySessions.remove(session.playerId(), session);
        SessionArbiter arbiter = arbiters.get(session.playerId());
        if (arbiter == null || arbiter.session != session) return;
        arbiters.remove(session.playerId(), arbiter);
        arbiter.close();
    }

    /** Uninstalls every arbiter and commits their pending flags. */
    public void close() {
        closed = true;
        readySessions.clear();
        for (SessionArbiter arbiter : arbiters.values()) {
            arbiters.remove(arbiter.session.playerId(), arbiter);
            arbiter.player.onLoop(() -> arbiter.close());
        }
    }

    int checkIndex(BridgeSession session, AbstractCheck check) {
        return checkIndex.applyAsInt(session, check);
    }

    void answered(BridgeSession session) {
        if (trippedServers.remove(session.serverName())) {
            logger.info("Backend flag verdicts from " + session.serverName() + " are answered again");
        }
    }

    /** @param trips true when this timeout stops the session's arbiter from deferring */
    void timedOut(BridgeSession session, int flagId, boolean trips) {
        logger.log(
                Level.FINE,
                () -> "Flag " + flagId + " for " + session.playerId() + " got no verdict from " + session.serverName()
                        + " within " + TIMEOUT_MILLIS + " ms; committing it");
        if (!trips) return;
        if (trippedServers.add(session.serverName())) {
            logger.warning(BREAKER_TIMEOUTS + " consecutive flag verdicts for " + session.playerId() + " from "
                    + session.serverName() + " timed out; that player's flags commit without a backend verdict"
                    + " until their next connection");
        } else {
            logger.log(
                    Level.FINE,
                    () -> "Flag verdicts for " + session.playerId() + " from " + session.serverName() + " timed out "
                            + BREAKER_TIMEOUTS + " times in a row; committing without them");
        }
    }

    /**
     * Sends the notify-only FLAG for a flag an arbiter declined after the API mirror left it to that arbiter, through
     * the player's current READY session when its backend listens for flags.
     */
    void notifyBackend(
            VerdictPlayer player, UUID playerId, AbstractCheck check, Supplier<String> verbose, double violations) {
        BridgeSession session = readySessions.get(playerId);
        if (session == null || !session.isReady() || !ListenerKind.has(session.listeners(), ListenerKind.FLAG)) {
            return;
        }
        int checkIdx = checkIndex(session, check);
        if (checkIdx < 0) return;
        String text = BridgeFrames.capVerbose(verbose.get());
        player.onLoop(() -> session.send(new Flag(BridgeFrames.NOTIFY_FLAG_ID, checkIdx, violations, text, false)));
    }

    private boolean shouldInstall(BridgeSession session) {
        return !closed && ListenerKind.has(session.listeners(), ListenerKind.FLAG);
    }
}
