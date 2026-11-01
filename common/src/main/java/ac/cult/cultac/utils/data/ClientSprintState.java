package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import org.jetbrains.annotations.Nullable;

/**
 * The Java client's sprint flag and sprint speed modifier, which MCP-Reborn writes independently.
 *
 * <ul>
 *   <li>ClientPacketListener#handleSetEntityData assigns the server's shared flags onto the local
 *       player, overwriting only Entity shared flag 3 (Entity#isSprinting).
 *   <li>ClientPacketListener#handleUpdateAttributes calls AttributeInstance#removeModifiers and
 *       re-adds the server's modifiers, overwriting only the minecraft:sprinting speed modifier.
 *   <li>LivingEntity#setSprinting writes the flag and removes/re-adds the modifier together.
 * </ul>
 *
 * The client applies both server packets in Minecraft#runTick's packetProcessor before ticking, so
 * between packet processing and LocalPlayer#aiStep the flag can hold the server's value. The client's
 * last START/STOP_SPRINTING (CultPlayer#isSprinting) is LocalPlayer#wasSprinting.
 */
public final class ClientSprintState {
    private boolean flag;
    // The minecraft:sprinting movement_speed modifier the client holds: the server's until setSprinting replaces it.
    private @Nullable AttributeModifier modifier;
    // An earlier possible attack reset that aiStep silently re-sprinted may also have replaced it with vanilla's.
    private boolean canonicalPossible;
    // This tick's possible attack reset: travel carries vanilla's modifier when Player#attack damped it.
    private boolean resetOutcomePending;
    // Counts the events that set the modifier with certainty; an older Outcomes snapshot cannot undo them.
    private int certainEvents;
    // The state the latest certain event left, which a restore of an older snapshot returns to.
    private Outcomes certainOutcomes = new Outcomes(null, false, 0, 0);
    // Counts possible attack resets; a restore keeps vanilla's modifier possible after any since its snapshot.
    private int possibleResets;
    private boolean sprintResetThisTick;
    private boolean flagUncertain;
    private boolean flagBeforeAiStep;
    private boolean aiStepReconciled;

    /** Whether Player#attack's causeExtraKnockback reached setSprinting(false). */
    public enum AttackReset {
        NONE,
        POSSIBLE,
        CERTAIN
    }

    /**
     * The minecraft:sprinting modifier a travel candidate moves with while a possible attack reset leaves it open.
     * Player#causeExtraKnockback damps deltaMovement by 0.6 and calls setSprinting(false) together, so a candidate
     * damped this tick moves with the modifier aiStep's re-sprint adds; any other candidate moves with either.
     */
    public enum Outcome {
        /** Every modifier the client may hold now. */
        ANY,
        /** The modifiers the client may have held before this tick's attack. */
        PRIOR,
        /** The modifier held unless a possible reset replaced it, or none. */
        HELD,
        /** Vanilla's modifier, which aiStep's re-sprint adds. */
        CANONICAL
    }

    /** Entity shared flag 3 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean sprinting) {
        flag = sprinting;
    }

    /** The minecraft:sprinting modifier of the server's movement_speed snapshot, applied by its bundled proof. */
    public void onServerModifier(@Nullable AttributeModifier sprinting) {
        modifier = sprinting;
        onCertainEvent();
    }

    /**
     * MultiPlayerGameMode#attack ran Player#attack before this tick's aiStep. When its causeExtraKnockback called
     * setSprinting(false), aiStep can re-sprint without a START_SPRINTING, re-adding the modifier.
     */
    public void onAttack(AttackReset reset) {
        if (reset == AttackReset.NONE) return;
        sprintResetThisTick = true;
        if (reset == AttackReset.CERTAIN) {
            flag = false;
            modifier = null;
            onCertainEvent();
        } else {
            flagUncertain = true;
        }
    }

    private void onCertainEvent() {
        canonicalPossible = false;
        resetOutcomePending = false;
        certainEvents++;
        certainOutcomes = outcomes();
    }

    /** Whether flag() is exact here: no attack earlier in this tick may have reset it unprovably. */
    public boolean flagKnown() {
        return !flagUncertain;
    }

    /**
     * Reconciles a completed LocalPlayer#aiStep with its known sprint flag. A tick that reaches
     * sendIsSprintingIfNeeded makes it equal the last announcement; a noncontrolling passenger can stop sprinting
     * without announcing it. A changed flag proves setSprinting ran and resynchronized the modifier. An already-false
     * flag does not remove a modifier installed independently by an attribute packet.
     */
    public void onClientTick(boolean sprinting) {
        if (!aiStepReconciled) {
            flagBeforeAiStep = flag;
            aiStepReconciled = true;
        }
        // Unmodeled: wall-sprinting start->stop in one aiStep can clear the modifier early; this only errs lenient.
        // An attack reset that aiStep re-sprinted re-added the modifier.
        if (flag != sprinting) {
            modifier = sprinting ? CompensatedEntities.SPRINTING_SPEED_MODIFIER : null;
            onCertainEvent();
        } else if (sprintResetThisTick && sprinting) {
            // Only a possible reset leaves the flag: Player#attack resets only after damage and positive knockback.
            resetOutcomePending = !CompensatedEntities.SPRINTING_SPEED_MODIFIER.equals(modifier);
            if (resetOutcomePending) possibleResets++;
        }
        flag = sprinting;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /**
     * A move is committed while {@link #outcomeOpen}. {@code canonical} and {@code held} say whether an accepted travel
     * candidate moved with {@link Outcome#CANONICAL} or {@link Outcome#HELD}; the modifier becomes the one the accepted
     * candidates prove, and stays open while candidates of both, or none, are accepted.
     */
    public void onOutcomesAccepted(boolean canonical, boolean held) {
        commit(outcomesAfter(canonical, held));
    }

    /** Takes {@code outcomes}, the state a committed move of this tick leaves, from {@link #outcomesAfter}. */
    public void commit(Outcomes outcomes) {
        modifier = outcomes.modifier();
        canonicalPossible = outcomes.canonicalPossible();
        resetOutcomePending = false;
    }

    /** The modifier state {@link #onOutcomesAccepted} would leave, without applying it. */
    public Outcomes outcomesAfter(boolean canonical, boolean held) {
        if (!outcomeOpen()) return outcomes();
        if (canonical && !held) {
            return new Outcomes(CompensatedEntities.SPRINTING_SPEED_MODIFIER, false, certainEvents, possibleResets);
        }
        return new Outcomes(modifier, canonical || !held, certainEvents, possibleResets);
    }

    /** The modifier state now. */
    public Outcomes outcomes() {
        return new Outcomes(modifier, canonicalPossible, certainEvents, possibleResets);
    }

    /**
     * Returns to {@code outcomes}, or to the state the latest event that set the modifier with certainty left when that
     * came later, discarding what moves committed since then implied. A possible attack reset since then is a client
     * event a teleport does not undo, so vanilla's modifier stays possible after it; this tick's still decides its
     * candidates by their damping.
     */
    public void restore(@Nullable Outcomes anchor) {
        if (anchor == null) return;
        Outcomes outcomes = anchor.certainEvents() == certainEvents ? anchor : certainOutcomes;
        int earlierResets = possibleResets - outcomes.possibleResets() - (resetOutcomePending ? 1 : 0);
        modifier = outcomes.modifier();
        canonicalPossible = outcomes.canonicalPossible()
                || earlierResets > 0 && !CompensatedEntities.SPRINTING_SPEED_MODIFIER.equals(modifier);
        if (CompensatedEntities.SPRINTING_SPEED_MODIFIER.equals(modifier)) resetOutcomePending = false;
    }

    /**
     * The tick's travel ended without a committed candidate deciding its possible reset: vanilla's modifier stays
     * possible beside the held one.
     */
    public void settleReset() {
        if (resetOutcomePending) canonicalPossible = true;
        resetOutcomePending = false;
    }

    /** A snapshot of the modifier the client holds and whether a possible reset may have replaced it. */
    public record Outcomes(
            @Nullable AttributeModifier modifier, boolean canonicalPossible, int certainEvents, int possibleResets) {}

    /**
     * A LocalPlayer#tick movement packet opens its tick, after every earlier tick's travel; the next
     * {@link #onClientTick} reconciles its aiStep.
     */
    public void onClientMove() {
        settleReset();
        aiStepReconciled = false;
    }

    /** The client's tick ended after its travel; the next {@link #onClientTick} reconciles the next tick's aiStep. */
    public void onClientTickEnd() {
        settleReset();
        aiStepReconciled = false;
    }

    /**
     * Entity#isSprinting as Entity#baseTick read it this tick, before LocalPlayer#aiStep: the previous tick's flag, or
     * the server's flag the client applied since.
     */
    public boolean flagBeforeAiStep() {
        return aiStepReconciled ? flagBeforeAiStep : flag;
    }

    /**
     * ClientPacketListener#handleRespawn. A kept entity-data respawn copies the old player's synched
     * data; a kept-attribute respawn copies its modifiers with AttributeInstance#replaceFrom.
     */
    public void onRespawn(boolean keepEntityData, boolean keepAttributeModifiers) {
        if (!keepEntityData) {
            flag = false;
        }
        if (!keepAttributeModifiers) {
            modifier = null;
            onCertainEvent();
        }
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer after Minecraft#clearClientLevel. */
    public void reset() {
        flag = false;
        modifier = null;
        onCertainEvent();
        flagBeforeAiStep = false;
        aiStepReconciled = false;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /** Entity#isSprinting as the client currently holds it. */
    public boolean flag() {
        return flag;
    }

    /** Whether the client's movement_speed may currently carry minecraft:sprinting. */
    public boolean modifier() {
        return modifier != null || canonicalPossible || resetOutcomePending;
    }

    /**
     * The minecraft:sprinting modifier the client's movement_speed carries unless a possible attack reset replaced it,
     * or null.
     */
    public @Nullable AttributeModifier heldModifier() {
        return modifier;
    }

    /** Whether an earlier possible attack reset may have replaced {@link #heldModifier} with vanilla's modifier. */
    public boolean canonicalPossible() {
        return canonicalPossible;
    }

    /** Whether this tick's possible attack reset decides each travel candidate's {@link Outcome}. */
    public boolean resetOutcomePending() {
        return resetOutcomePending;
    }

    /** Whether the next committed move may still tell the {@link Outcome#HELD} and canonical modifiers apart. */
    public boolean outcomeOpen() {
        return resetOutcomePending || canonicalPossible;
    }

    /**
     * The outcomes a candidate with {@code attackSlows} dampings can move with while {@link #outcomeOpen}: vanilla's
     * modifier when this tick's possible reset damped it, else the held modifier or, after an earlier possible reset,
     * either.
     */
    public Outcome[] outcomesFor(int attackSlows) {
        if (resetOutcomePending && attackSlows > 0) return new Outcome[] {Outcome.CANONICAL};
        return canonicalPossible ? new Outcome[] {Outcome.HELD, Outcome.CANONICAL} : new Outcome[] {Outcome.HELD};
    }
}
