package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The likely server view of item use, paired with the client ticks claimed during it.
 *
 * <p>{@code ServerPlayer#doTick} decrements {@code useItemRemaining} once per server tick,
 * consumption completes only server-side (entity event 9), and crossbow/bow/trident charge from
 * that counter at {@code RELEASE_USE_ITEM}. A use therefore buys real time, which the client must
 * pay for with slowed ticks.
 *
 * <p>A use starts from the inbound use packet (the client sent it earlier) and is confirmed only
 * by the server's own self flags: {@code MinecraftServer#tickChildren} runs the level tick (self
 * {@code DATA_LIVING_ENTITY_FLAGS}) before the connection tick ({@code BlockChangedAck} of that
 * use's sequence), so an acknowledgement without the flag proves the server rejected the use.
 * Outbound end packets of one use always precede the next use's confirming flag, so end packets
 * observed before that confirmation can never end the next use.
 *
 * <p>The client stops locally once its hand holds another item (LivingEntity#updatingUsingItem),
 * which can precede the server's stop. That local stop is only tentative: the server confirms it
 * with its flag clear, or refutes it by sending the used item back into that hand.
 * ServerGamePacketListenerImpl#handleContainerClick corrects a rejected click claim before its
 * next tick, and the client does not resume using a returned item, so a refuted stop leaves the
 * server using an item the client is not slowed by: those ticks are charged.
 *
 * <p>LocalPlayer#onSyncedDataUpdated stops a started use when it processes a flag clear, so a clear
 * acknowledged after the next use packet arrived stops the client's new use while the server, already
 * past that clear, keeps using. Minecraft#handleKeybinds restarts it with another use packet once
 * rightClickDelay expires, or the confirming flag restarts it (LocalPlayer#onSyncedDataUpdated) at its
 * proof. Packets reach the client in send order, so the pause excludes at most the server's send gap
 * between that clear and the confirming flag; time past it is charged. A release during the pause,
 * which a vanilla client that is not using never sends, refutes it.
 *
 * <p>Owner-thread only. Times are local nanoseconds at this connection, never client claims.
 */
public final class ServerItemUseView {
    public static final int NO_PROOF = Integer.MIN_VALUE;
    private static final int HISTORY = 64;
    private static final int MAX_CANDIDATES = 8;

    /** A finished movement tick's residual input, evaluated at a use slowdown. */
    @FunctionalInterface
    public interface Evidence {
        double offset(float multiplier);
    }

    /** Server-view use effects. {@code itemKey} is null when the held item is not uniquely known. */
    public record Profile(float multiplier, int duration, String itemKey) {}

    @FunctionalInterface
    public interface Resolver {
        Profile resolve(boolean offhand, List<SimItemStack> captured);
    }

    /** Real time the server spent using, in client ticks, against the client ticks claimed meanwhile. */
    public record Outcome(double expected, int ticks, int unslowed, boolean inProgress) {
        public int paid() {
            return ticks - unslowed;
        }

        public double debt() {
            return expected - paid();
        }
    }

    private static final class Candidate {
        final boolean offhand;
        final int sequence;
        final long start;
        final int firstTick;
        final List<SimItemStack> captured;
        long maxPeriod;
        long endedAt = Long.MIN_VALUE;
        int endTick;
        boolean lastTickMayBeLocal;
        // A stale flag clear stopped the client after this tick; the server keeps the use.
        long pausedAt = Long.MIN_VALUE;
        int pausedTick;
        long pauseClearSentAt;

        Candidate(boolean offhand, int sequence, long start, int firstTick, List<SimItemStack> captured, long period) {
            this.offhand = offhand;
            this.sequence = sequence;
            this.start = start;
            this.firstTick = firstTick;
            this.captured = captured;
            this.maxPeriod = period;
        }
    }

    private static final class Session {
        final boolean offhand;
        final Profile profile;
        // When the server could first have started; start is rebased once a debt is settled.
        final long begun;
        long start;
        long maxPeriod;
        int ticks;
        int unslowed;
        boolean lastUnslowed;
        long serverEnd = Long.MIN_VALUE;
        int proof = NO_PROOF;
        // The client-view hand held the used item; only then can it stop by losing it.
        boolean clientHeld;
        // Tentative local stop, after the client's last tick that could still be using.
        long stopTime = Long.MIN_VALUE;
        int stopTicks;
        int stopUnslowed;
        // The proof of the confirming flag; the client restarts a paused use when it processes it.
        int startProof = NO_PROOF;
        long flagSentAt;
        boolean clientStarted;
        // A stale flag clear paused the client's use after this time; ticks since then are not counted.
        long pausedAt = Long.MIN_VALUE;
        // The send gap from that clear to the confirming flag: the most server time the pause excludes.
        long pauseCap;
        int pausedTicks;
        int pausedUnslowed;

        Session(boolean offhand, Profile profile, long start, long period) {
            this.offhand = offhand;
            this.profile = profile;
            this.begun = start;
            this.start = start;
            this.maxPeriod = period;
        }

        boolean serverActive() {
            return serverEnd == Long.MIN_VALUE;
        }

        boolean tracked() {
            return profile.multiplier() < 1;
        }

        boolean stoppedLocally() {
            return stopTime != Long.MIN_VALUE;
        }

        boolean paused() {
            return pausedAt != Long.MIN_VALUE;
        }

        void pause(long at, long clearSentAt) {
            pausedAt = at;
            pauseCap = Math.max(0, flagSentAt - clearSentAt);
            pausedTicks = ticks;
            pausedUnslowed = unslowed;
        }

        void resume(long at) {
            start += Math.min(Math.max(0, at - pausedAt), pauseCap);
            ticks = pausedTicks;
            unslowed = pausedUnslowed;
            pausedAt = Long.MIN_VALUE;
        }

        void endAtPause(long at) {
            resume(at);
            serverEnd = at;
            lastUnslowed = false;
        }

        void endAtLocalStop() {
            serverEnd = stopTime;
            ticks = stopTicks;
            unslowed = stopUnslowed;
            lastUnslowed = false;
        }
    }

    private final Evidence[] history = new Evidence[HISTORY];
    private final ArrayDeque<Candidate> candidates = new ArrayDeque<>();
    private final Resolver resolver;
    private final Consumer<Outcome> onFinished;
    // Returns true when the provisional debt was settled, rebasing the use at the proven clock.
    private final Predicate<Outcome> onProgress;
    // Lazily formatted model decisions for item-use debugging.
    private final Consumer<Supplier<String>> trace;
    private Session session;
    private int tick;
    private long lastTickEnd = Long.MIN_VALUE;
    private boolean pendingClear;
    // The newest flag clear the client has not acknowledged yet.
    private int clearProof = NO_PROOF;
    private long clearSentAt;
    private int serverSlot = -1;
    private long period = 50_000_000L;
    private double threshold = 1.0E-5;
    private double charge = 1;

    public ServerItemUseView(
            Resolver resolver,
            Consumer<Outcome> onFinished,
            Predicate<Outcome> onProgress,
            Consumer<Supplier<String>> trace) {
        this.resolver = resolver;
        this.onFinished = onFinished;
        this.onProgress = onProgress;
        this.trace = trace;
    }

    public void threshold(double threshold) {
        this.threshold = threshold;
    }

    /** The fraction of each expected use tick that must be paid with a slowed tick. */
    public void charge(double charge) {
        if (!Double.isFinite(charge) || charge < 0) throw new IllegalArgumentException("Invalid item-use charge");
        this.charge = charge;
    }

    /** The client tick period that currently bounds the client's tick rate. Raises open uses' periods. */
    public void period(long period) {
        this.period = period;
        if (session != null) session.maxPeriod = Math.max(session.maxPeriod, period);
        for (Candidate candidate : candidates) candidate.maxPeriod = Math.max(candidate.maxPeriod, period);
    }

    /** The server's selected hotbar slot, or {@code fallback} before the first observation. */
    public int serverSlot(int fallback) {
        return serverSlot < 0 ? fallback : serverSlot;
    }

    /** Whether movement evidence is needed for the next tick. */
    public boolean tracking() {
        return session != null || !candidates.isEmpty();
    }

    /** The hand and item a server-view use is bound to, or null when no use is active server-side. */
    public String activeItem(boolean offhand) {
        return session != null && session.serverActive() && session.offhand == offhand
                ? session.profile.itemKey()
                : null;
    }

    /** ServerboundUseItem/ServerboundUseItemOn, at arrival. */
    public void use(boolean offhand, int sequence, long now, List<SimItemStack> captured) {
        // LivingEntity#startUsingItem is a no-op while the server is already using an item.
        if (session != null && session.serverActive()) {
            if (session.paused() && session.offhand == offhand) {
                trace(() -> "use seq=" + sequence + " restarts the paused client use");
                session.resume(now);
                return;
            }
            trace(() -> "use seq=" + sequence + " " + hand(offhand) + " ignored: server already using "
                    + item(session.profile));
            return;
        }
        // A vanilla client cannot send a use before processing the previous stop, so the
        // previous use's client window cannot extend past this packet.
        if (session != null) finish(session, false);
        if (candidates.size() == MAX_CANDIDATES) candidates.removeFirst();
        candidates.addLast(new Candidate(offhand, sequence, now, tick, List.copyOf(captured), period));
        trace(() -> "use seq=" + sequence + " " + hand(offhand) + " candidate, holding "
                + captured.stream().map(SimItemStack::itemKey).toList());
    }

    /** ClientboundBlockChangedAck: the server has processed every use up to {@code sequence}. */
    public void serverAck(int sequence) {
        // The level tick's self flags precede this connection-tick acknowledgement.
        int before = candidates.size();
        candidates.removeIf(candidate -> candidate.sequence <= sequence);
        int rejected = before - candidates.size();
        if (rejected > 0)
            trace(() -> "ack seq=" + sequence + " without a use flag: server rejected " + rejected + " candidate(s)");
    }

    /** Self DATA_LIVING_ENTITY_FLAGS, at send. */
    public void serverFlags(boolean active, boolean offhand, int proof, long now) {
        if (!active) {
            if (proof != NO_PROOF) {
                clearProof = proof;
                clearSentAt = now;
            }
            // The flag clear that follows a stop already accounted for at its cause.
            if (pendingClear) {
                pendingClear = false;
                trace(() -> "flags clear: follows a stop already accounted");
                return;
            }
            if (session == null || !session.serverActive()) return;
            if (session.paused()) {
                trace(() -> "flags clear: the server stops the paused use");
                session.endAtPause(now);
                finish(session, false);
            } else if (session.stoppedLocally()) {
                // The server confirms the client's local stop: neither side used the item after it.
                trace(() -> "flags clear: server confirms the local stop at " + at(session.stopTime));
                session.endAtLocalStop();
                finish(session, false);
            } else {
                trace(() -> "flags clear at " + at(now) + ", " + proof(proof));
                serverEnd(now, proof);
            }
            return;
        }
        if (session != null && session.serverActive()) return;
        // Any clear owed by an earlier use was sent before this flag; it can no longer arrive.
        pendingClear = false;
        Candidate candidate = null;
        for (var it = candidates.descendingIterator(); it.hasNext(); ) {
            Candidate next = it.next();
            if (next.offhand == offhand) {
                candidate = next;
                break;
            }
        }
        candidates.clear();
        if (session != null) finish(session, false);
        var profile = resolver.resolve(offhand, candidate == null ? List.of() : candidate.captured);
        // Without a matching use packet (a plugin start), the observation itself bounds the start.
        session = new Session(
                offhand,
                profile,
                candidate == null ? now : candidate.start,
                candidate == null ? period : Math.max(period, candidate.maxPeriod));
        session.startProof = proof;
        session.flagSentAt = now;
        session.clientStarted = candidate != null;
        var started = session;
        if (candidate == null) {
            trace(() -> "flags use " + hand(offhand) + " without a use packet (plugin start): "
                    + describe(started.profile));
            return;
        }
        int end = candidate.endedAt == Long.MIN_VALUE ? tick : candidate.endTick;
        for (int i = candidate.firstTick + 1; i <= end; i++) {
            if (candidate.pausedAt != Long.MIN_VALUE && i == candidate.pausedTick + 1)
                session.pause(candidate.pausedAt, candidate.pauseClearSentAt);
            count(session, i > tick - HISTORY ? history[Math.floorMod(i, HISTORY)] : null);
        }
        if (candidate.pausedAt != Long.MIN_VALUE && !session.paused())
            session.pause(candidate.pausedAt, candidate.pauseClearSentAt);
        var confirmed = candidate;
        trace(() -> "flags use " + hand(offhand) + " confirms seq=" + confirmed.sequence + " at " + at(now) + ": "
                + describe(started.profile) + ", replayed ticks=" + started.ticks + " unslowed=" + started.unslowed);
        if (candidate.endedAt != Long.MIN_VALUE) {
            // The use was already stopped by a later packet the server processes in order.
            trace(() -> "use already stopped at " + at(confirmed.endedAt) + " by a later packet");
            session.serverEnd = candidate.endedAt;
            pendingClear = true;
            finish(session, candidate.lastTickMayBeLocal);
        }
    }

    /**
     * Entity event 9, at send. Only attributed when the active use could have completed by now:
     * an early event cannot be this use's completion, which is at least duration - 1 server
     * ticks after its start.
     */
    public void serverComplete(int proof, long now, long serverTickNanos) {
        if (session == null || !session.serverActive()) return;
        int duration = session.profile.duration();
        if (duration > 1 && (now - session.begun) * 2 < (long) (duration - 1) * serverTickNanos) {
            trace(() -> "event 9 at " + at(now) + " ignored: too early for a " + duration + " tick use");
            return;
        }
        trace(() -> "event 9 at " + at(now) + ", " + proof(proof)
                + (session.stoppedLocally() ? ", refutes the local stop" : ""));
        // updatingUsingItem stops a changed item before it can complete, so a local stop was refuted.
        session.stopTime = Long.MIN_VALUE;
        if (session.paused()) session.resume(now);
        serverEnd(now, proof);
        pendingClear = true;
    }

    /** RELEASE_USE_ITEM: LivingEntity#releaseUsingItem always stops use. */
    public void release(long now) {
        // Minecraft#handleKeybinds only releases while the client is still using.
        if (session != null) {
            session.stopTime = Long.MIN_VALUE;
            if (session.paused()) trace(() -> "release refutes the pause");
            session.pausedAt = Long.MIN_VALUE;
        }
        for (Candidate candidate : candidates) candidate.pausedAt = Long.MIN_VALUE;
        stopAtArrival("release", false, now, false);
    }

    /** SWAP_ITEM_WITH_OFFHAND: the server swaps hands and stops use. */
    public void swapHands(long now) {
        stopAtArrival("swap hands", false, now, false);
    }

    /** ServerboundSetCarriedItem. handleSetCarriedItem stops MAIN_HAND use on a different slot. */
    public void selectSlot(int slot, int fallback, long now) {
        if (slot < 0 || slot > 8) return;
        // Minecraft#tick sends the selected slot before handleKeybinds, after a scroll or key
        // selected it locally, so the client's last claimed use tick may already be unslowed.
        if (serverSlot(fallback) != slot) stopAtArrival("select slot " + slot, true, now, true);
        serverSlot = slot;
    }

    /** ClientboundSetHeldSlot, at send. The server's next entity tick stops a changed item. */
    public void serverSelectSlot(int slot) {
        if (slot >= 0 && slot <= 8) serverSlot = slot;
    }

    /**
     * Whether the client-view hand holds the used item before this tick. Losing it stops the
     * client at this tick's entity tick, so the use tentatively ends after the previous tick;
     * regaining it means the server never took it.
     */
    public void clientHolds(boolean holds) {
        if (session == null || !session.serverActive()) return;
        if (holds) {
            if (session.stoppedLocally()) trace(() -> "client view holds the item again: local stop refuted");
            else if (!session.clientHeld) trace(() -> "client view holds " + item(session.profile));
            session.clientHeld = true;
            session.stopTime = Long.MIN_VALUE;
        } else if (session.clientHeld && !session.stoppedLocally()) {
            session.stopTime = Math.max(session.start, lastTickEnd);
            session.stopTicks = session.ticks;
            session.stopUnslowed = session.unslowed;
            trace(() -> "client view lost " + item(session.profile) + ": tentative local stop at "
                    + at(session.stopTime) + " after ticks=" + session.stopTicks);
        }
    }

    /**
     * The server sent the used item into the used hand, or never received the click that
     * removed it: it is still using the item, so the client's local stop is refuted.
     */
    public void serverKeepsItem(String cause) {
        if (session == null || !session.serverActive()) return;
        trace(() -> cause + ": server keeps " + item(session.profile)
                + (session.stoppedLocally() ? ", local stop refuted" : ""));
        session.stopTime = Long.MIN_VALUE;
        // Until the client view holds the item again (the correction at its proof), it cannot
        // claim another local stop; withholding that proof keeps every tick charged.
        session.clientHeld = false;
    }

    /** A transaction the client acknowledged, in client order, at its arrival. */
    public void acknowledge(int transaction, long now) {
        if (transaction == clearProof) {
            clearProof = NO_PROOF;
            pauseStartedUses();
        }
        if (session == null) return;
        if (session.serverActive()) {
            if (session.paused() && session.startProof == transaction) {
                trace(() -> "proof " + transaction + " acknowledged: the confirming flag restarts the paused use");
                session.resume(now);
            }
            return;
        }
        if (session.proof != transaction) return;
        trace(() -> "proof " + transaction + " acknowledged: client processed the stop");
        finish(session, false);
    }

    /** The client processed a flag clear after sending every pending use: each started use stops. */
    private void pauseStartedUses() {
        for (Candidate candidate : candidates) {
            if (candidate.endedAt != Long.MIN_VALUE || candidate.pausedAt != Long.MIN_VALUE) continue;
            candidate.pausedAt = Math.max(candidate.start, lastTickEnd);
            candidate.pausedTick = Math.max(candidate.firstTick, tick);
            candidate.pauseClearSentAt = clearSentAt;
            trace(() -> "stale flags clear acknowledged after use seq=" + candidate.sequence + ": client pauses");
        }
        if (session == null || !session.serverActive() || !session.clientStarted || session.paused()) return;
        session.pause(Math.max(session.start, lastTickEnd), clearSentAt);
        trace(() -> "stale flags clear acknowledged during the use: client pauses after ticks=" + session.ticks);
    }

    /** ServerboundClientTickEnd. {@code clockAtLeast} is a proven lower bound on the client's time. */
    public void tickEnd(Evidence evidence, long clockAtLeast, long now) {
        tick++;
        lastTickEnd = now;
        history[Math.floorMod(tick, HISTORY)] = evidence;
        if (session == null) return;
        count(session, evidence);
        // An unresolved local stop is settled by the server's next packets, not provisionally.
        if (!session.serverActive()
                || !session.tracked()
                || session.stoppedLocally()
                || session.paused()
                || clockAtLeast <= session.start)
            return;
        var outcome = new Outcome(
                (clockAtLeast - session.start) * charge / session.maxPeriod, session.ticks, session.unslowed, true);
        if (onProgress.test(outcome)) {
            session.start = clockAtLeast;
            session.ticks = 0;
            session.unslowed = 0;
        }
    }

    public void reset() {
        if (tracking()) trace(() -> "reset");
        candidates.clear();
        session = null;
        pendingClear = false;
        clearProof = NO_PROOF;
        serverSlot = -1;
        Arrays.fill(history, null);
        lastTickEnd = Long.MIN_VALUE;
    }

    private void stopAtArrival(String cause, boolean mainHandOnly, long now, boolean lastTickMayBeLocal) {
        int ended = 0;
        for (Candidate candidate : candidates) {
            if (!(mainHandOnly && candidate.offhand) && candidate.endedAt == Long.MIN_VALUE) {
                candidate.endedAt = now;
                candidate.endTick = tick;
                candidate.pausedAt = Long.MIN_VALUE;
                candidate.lastTickMayBeLocal = lastTickMayBeLocal;
                ended++;
            }
        }
        int endedCandidates = ended;
        if (ended > 0) trace(() -> cause + " ends " + endedCandidates + " unconfirmed candidate(s)");
        if (session == null || !session.serverActive() || (mainHandOnly && session.offhand)) return;
        pendingClear = true;
        if (session.paused()) {
            trace(() -> cause + " ends the paused use at " + at(now));
            session.endAtPause(now);
            finish(session, false);
            return;
        }
        if (session.stoppedLocally()) {
            trace(() -> cause + " ends the use at its local stop " + at(session.stopTime));
            session.endAtLocalStop();
            finish(session, false);
            return;
        }
        trace(() -> cause + " ends the use at " + at(now));
        session.serverEnd = now;
        finish(session, lastTickMayBeLocal);
    }

    private void serverEnd(long now, int proof) {
        session.serverEnd = now;
        session.proof = proof;
        if (proof == NO_PROOF) finish(session, false);
    }

    private void count(Session session, Evidence evidence) {
        session.ticks++;
        double offset = session.tracked() && evidence != null ? evidence.offset(session.profile.multiplier()) : 0;
        session.lastUnslowed = offset > threshold;
        if (!session.lastUnslowed) return;
        session.unslowed++;
        trace(() -> String.format(
                Locale.ROOT,
                "tick unslowed: offset=%.5f above %.5f at x%.2f (ticks=%d unslowed=%d)",
                offset,
                threshold,
                session.profile.multiplier(),
                session.ticks,
                session.unslowed));
    }

    private void finish(Session finished, boolean lastTickMayBeLocal) {
        if (session == finished) session = null;
        if (!finished.tracked()) {
            trace(() -> "use of " + item(finished.profile) + " ended; it has no slowdown");
            return;
        }
        int unslowed = finished.unslowed - (lastTickMayBeLocal && finished.lastUnslowed ? 1 : 0);
        double expected = Math.max(0, finished.serverEnd - finished.start) * charge / finished.maxPeriod;
        onFinished.accept(new Outcome(expected, finished.ticks, unslowed, false));
    }

    private void trace(Supplier<String> message) {
        int at = tick;
        trace.accept(() -> "t=" + at + " " + message.get());
    }

    /** Time since the current use's packet arrived. */
    private String at(long time) {
        return session == null ? "?" : String.format(Locale.ROOT, "+%.1fms", (time - session.begun) / 1.0E6);
    }

    private static String hand(boolean offhand) {
        return offhand ? "offhand" : "main hand";
    }

    private static String item(Profile profile) {
        return profile.itemKey() == null ? "an ambiguous item" : profile.itemKey();
    }

    private static String proof(int proof) {
        return proof == NO_PROOF ? "no proof" : "proof " + proof;
    }

    private static String describe(Profile profile) {
        return String.format(
                Locale.ROOT, "%s x%.2f duration=%d", item(profile), profile.multiplier(), profile.duration());
    }

    /**
     * Use effects of the item the server is most likely using. Only stacks with a use are
     * candidates; when none is known the server still proved a use, so vanilla's default
     * {@code UseEffects} applies. Multiple candidates take the weakest slowdown.
     */
    public static Profile profile(List<SimItemStack> stacks, ClientVersion version) {
        float multiplier = -1;
        int duration = 0;
        String key = null;
        boolean uniqueKey = true;
        boolean uniqueDuration = true;
        for (SimItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            int ticks = ClientItemUseState.duration(stack);
            if (ticks == 0) continue;
            multiplier = Math.max(
                    multiplier, ClientItemUseState.effects(stack, version).speedMultiplier());
            if (key == null) {
                key = stack.itemKey();
                duration = ticks;
            } else {
                uniqueKey &= key.equals(stack.itemKey());
                uniqueDuration &= duration == ticks;
            }
        }
        if (multiplier < 0) return new Profile(ItemComponents.UseEffects.DEFAULT.speedMultiplier(), 0, null);
        return new Profile(multiplier, uniqueDuration ? duration : 0, uniqueKey ? key : null);
    }
}
