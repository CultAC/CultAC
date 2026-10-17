package ac.cult.cultac.platform.velocity.bridge.verdict;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.verdict.FlagArbiter;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.platform.velocity.bridge.BridgeFrames;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Hands one bridge session's flags to its backend for a verdict.
 *
 * <p>{@link #defer} runs on the player's packet thread; every other method runs on the client channel loop. A pending
 * outcome is resolved by whichever of verdict, timeout or close removes it from {@link #pending} first.
 *
 * <p>After {@link VerdictService#BREAKER_TIMEOUTS} consecutive timeouts the arbiter stops deferring for the rest of its
 * session; a VERDICT before that resets the count, and the next session starts with a fresh arbiter.
 */
final class SessionArbiter implements FlagArbiter {
    final BridgeSession session;
    final VerdictPlayer player;
    private final VerdictService service;
    private final Map<Integer, FlagOutcome> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextFlagId = new AtomicInteger(1);
    private volatile boolean closed;
    private boolean installed;
    private boolean tripped;
    private int consecutiveTimeouts;

    SessionArbiter(BridgeSession session, VerdictPlayer player, VerdictService service) {
        this.session = session;
        this.player = player;
        this.service = service;
    }

    @Override
    public boolean defer(
            @NotNull Check check,
            @NotNull Supplier<String> verbose,
            double prospectiveViolations,
            @NotNull FlagOutcome outcome) {
        if (closed) return declined(check, verbose, prospectiveViolations);
        int checkIdx = service.checkIndex(session, check);
        if (checkIdx < 0) return declined(check, verbose, prospectiveViolations);
        String text = BridgeFrames.capVerbose(verbose.get());
        int flagId = nextFlagId.getAndIncrement();
        pending.put(flagId, outcome);
        // A close that drained this outcome has already queued its commit.
        if (closed) {
            return pending.remove(flagId) == null || declined(check, verbose, prospectiveViolations);
        }
        player.onLoop(() -> send(flagId, checkIdx, prospectiveViolations, text));
        return true;
    }

    /** The publisher skipped this flag's notify FLAG because this arbiter owned it; the flag commits locally. */
    private boolean declined(Check check, Supplier<String> verbose, double violations) {
        service.notifyBackend(player, session.playerId(), check, verbose, violations);
        return false;
    }

    void refresh(boolean shouldInstall) {
        if (tripped) shouldInstall = false;
        if (closed || shouldInstall == installed) return;
        installed = shouldInstall;
        if (shouldInstall) player.install(this);
        else player.uninstall(this);
    }

    void verdict(int flagId, boolean cancelled) {
        FlagOutcome outcome = pending.remove(flagId);
        if (outcome == null) return;
        if (!tripped) consecutiveTimeouts = 0;
        service.answered(session);
        player.resolveLater(cancelled ? outcome::discard : outcome::commit);
    }

    /** Uninstalls the arbiter and commits every pending flag, as an unanswered verdict is not a cancel. */
    void close() {
        if (closed) return;
        closed = true;
        installed = false;
        player.uninstall(this);
        for (Integer flagId : pending.keySet()) {
            FlagOutcome outcome = pending.remove(flagId);
            if (outcome != null) player.resolveLater(outcome::commit);
        }
    }

    private void send(int flagId, int checkIdx, double violations, String verbose) {
        if (!pending.containsKey(flagId)) return;
        session.send(new Flag(flagId, checkIdx, violations, verbose, true));
        player.schedule(() -> timedOut(flagId), VerdictService.TIMEOUT_MILLIS);
    }

    private void timedOut(int flagId) {
        FlagOutcome outcome = pending.remove(flagId);
        if (outcome == null) return;
        boolean trips = !tripped && ++consecutiveTimeouts >= VerdictService.BREAKER_TIMEOUTS;
        service.timedOut(session, flagId, trips);
        if (trips) {
            tripped = true;
            refresh(false);
        }
        player.resolveLater(outcome::commit);
    }
}
