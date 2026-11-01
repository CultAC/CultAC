package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import org.junit.jupiter.api.Test;

class ClientSprintStateTest {
    private static ClientSprintState sprinting() {
        ClientSprintState state = new ClientSprintState();
        // LocalPlayer#aiStep -> setSprinting(true), then START_SPRINTING.
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
        return state;
    }

    @Test
    void serverSprintModifierHoldsUntilSetSprinting() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        // The flag stays set, so aiStep never calls setSprinting.
        state.onClientTick(true);
        assertSame(server, state.heldModifier());
        state.onClientTick(false);
        assertNull(state.heldModifier());
        state.onClientTick(true);
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, state.heldModifier());
    }

    @Test
    void possibleAttackResetFollowsTheOutcomesTheAcceptedMovesProve() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        // Player#attack may or may not have reached causeExtraKnockback; aiStep keeps sprinting either way.
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        assertTrue(state.resetOutcomePending());
        assertTrue(state.modifier());
        // Only undamped candidates were accepted: the reset did not happen.
        state.onOutcomesAccepted(false, true);
        assertSame(server, state.heldModifier());
        assertFalse(state.canonicalPossible());
        assertFalse(state.resetOutcomePending());

        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        // Candidates of both outcomes were accepted: either modifier stays possible.
        state.onOutcomesAccepted(true, true);
        assertSame(server, state.heldModifier());
        assertTrue(state.canonicalPossible());
        // A later tick whose accepted candidates all moved at the held speed resolves the tie.
        state.onClientTick(true);
        assertTrue(state.outcomeOpen());
        state.onOutcomesAccepted(false, true);
        assertSame(server, state.heldModifier());
        assertFalse(state.outcomeOpen());
        // Ticks after a tie that accept both speeds keep it, and one that accepts only vanilla's resolves it.
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(true, true);
        state.onClientTick(true);
        state.onOutcomesAccepted(true, true);
        assertTrue(state.canonicalPossible());
        state.onClientTick(true);
        state.onOutcomesAccepted(true, false);
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, state.heldModifier());
        assertFalse(state.outcomeOpen());
        state.onServerModifier(server);

        // Only damped candidates were accepted: aiStep re-sprinted with vanilla's modifier.
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(true, false);
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, state.heldModifier());
        assertFalse(state.canonicalPossible());
    }

    @Test
    void restoringAnEarlierStateDropsLaterOutcomesButNotCertainEvents() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(true, true);
        ClientSprintState.Outcomes tie = state.outcomes();
        // A move the engine later sets back from resolved the tie.
        state.onClientTick(true);
        state.onOutcomesAccepted(true, false);
        assertFalse(state.outcomeOpen());
        state.restore(tie);
        assertSame(server, state.heldModifier());
        assertTrue(state.canonicalPossible());

        // A server snapshot after the earlier state is certain and survives the restore.
        state.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
        state.restore(tie);
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, state.heldModifier());
        assertFalse(state.canonicalPossible());
    }

    @Test
    void restoringPastAPossibleAttackResetKeepsVanillasModifierPossible() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        ClientSprintState.Outcomes anchor = state.outcomes();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(true, false);
        assertFalse(state.outcomeOpen());
        // The teleport does not undo the attack's reset, which aiStep may have followed with vanilla's modifier.
        state.restore(anchor);
        assertSame(server, state.heldModifier());
        assertTrue(state.canonicalPossible());

        // This tick's own possible reset keeps deciding its candidates by their damping.
        state.onServerModifier(server);
        anchor = state.outcomes();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.restore(anchor);
        assertTrue(state.resetOutcomePending());
        assertFalse(state.canonicalPossible());
        // Set back without a committed candidate, the reset stays undecided.
        state.settleReset();
        assertTrue(state.canonicalPossible());
        assertFalse(state.resetOutcomePending());
    }

    @Test
    void aPossibleResetNoCommitDecidedStaysPossibleAfterItsTravel() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        state.onClientMove();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        assertTrue(state.resetOutcomePending());
        // The move is discarded unsimulated; the next tick's movement follows its travel.
        state.onClientMove();
        assertFalse(state.resetOutcomePending());
        assertSame(server, state.heldModifier());
        assertTrue(state.canonicalPossible());

        // A tick without a movement packet travels at its end, before the tick end settles it.
        state.onServerModifier(server);
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(false, true);
        state.onClientTickEnd();
        assertSame(server, state.heldModifier());
        assertFalse(state.canonicalPossible());
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onClientTickEnd();
        assertFalse(state.resetOutcomePending());
        assertTrue(state.canonicalPossible());
    }

    @Test
    void restoringAnAnchorOlderThanACertainEventReturnsToThatEvent() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        ClientSprintState.Outcomes anchor = state.outcomes();
        state.onServerModifier(server);
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(false, true);
        assertFalse(state.canonicalPossible());
        state.restore(anchor);
        assertSame(server, state.heldModifier());
        assertTrue(state.canonicalPossible());

        // The certain event itself stays.
        anchor = state.outcomes();
        var other = new AttributeModifier("minecraft:sprinting", 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(other);
        state.restore(anchor);
        assertSame(other, state.heldModifier());
        assertFalse(state.canonicalPossible());
    }

    @Test
    void possibleAttackResetOutcomesCollapseOnCertainEvents() {
        ClientSprintState state = sprinting();
        var server = new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        state.onServerModifier(server);
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        state.onOutcomesAccepted(false, false);
        assertTrue(state.canonicalPossible());
        // The server's next movement_speed snapshot is certain.
        state.onServerModifier(server);
        assertFalse(state.canonicalPossible());

        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        // LivingEntity#setSprinting(false) removes the modifier in either outcome.
        state.onClientTick(false);
        assertNull(state.heldModifier());
        assertFalse(state.resetOutcomePending());
        state.onOutcomesAccepted(true, false);
        assertNull(state.heldModifier());
        assertFalse(state.canonicalPossible());
    }

    @Test
    void certainAttackResetResprintsWithVanillasModifier() {
        ClientSprintState state = sprinting();
        state.onServerModifier(
                new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        state.onAttack(ClientSprintState.AttackReset.CERTAIN);
        state.onClientTick(true);
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, state.heldModifier());
        assertFalse(state.canonicalPossible());
    }

    @Test
    void silentResprintAfterServerResetRestoresModifier() {
        ClientSprintState state = sprinting();
        // Server-side Player#causeExtraKnockback echoes sprint=false in entity data and attributes.
        state.onServerFlag(false);
        state.onServerModifier(null);
        assertFalse(state.flag());
        // aiStep re-sprints; wasSprinting is still true, so no START_SPRINTING is sent.
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void staleServerStartIsStoppedByAiStep() {
        ClientSprintState state = sprinting();
        state.onClientTick(false);
        assertFalse(state.modifier());
        // The server's echo of the earlier START_SPRINTING arrives after the client stopped.
        state.onServerFlag(true);
        state.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
        // shouldStopRunSprinting -> setSprinting(false); wasSprinting is already false.
        state.onClientTick(false);
        assertFalse(state.flag());
        assertFalse(state.modifier());
    }

    @Test
    void attributeOnlyUpdateKeepsFlagUntilNextTransition() {
        ClientSprintState state = sprinting();
        // A movement_speed sync while the server believes the player is not sprinting.
        state.onServerModifier(null);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertFalse(state.modifier());

        state.onClientTick(false);
        state.onClientTick(true);
        assertTrue(state.modifier());
    }

    @Test
    void attributeOnlyModifierWithoutSprintFlag() {
        ClientSprintState state = new ClientSprintState();
        state.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
        state.onClientTick(false);
        assertFalse(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void attackResetThatAiStepResprintedRestoresModifier() {
        ClientSprintState state = sprinting();
        // A movement_speed sync while the server believes the player is not sprinting.
        state.onServerModifier(null);
        state.onClientTick(true);
        assertFalse(state.modifier());
        // Player#causeExtraKnockback -> setSprinting(false), then aiStep re-sprints without a packet.
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void attackWithoutResprintEndsWithoutModifier() {
        ClientSprintState state = sprinting();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        // The reset was not re-sprinted, so LocalPlayer sent STOP_SPRINTING.
        state.onClientTick(false);
        assertFalse(state.flag());
        assertFalse(state.modifier());
        // The attack only bounds the tick it happened in.
        state.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
        state.onClientTick(false);
        assertTrue(state.modifier());
    }

    @Test
    void attackThatCannotResetSprintKeepsDesyncedModifier() {
        ClientSprintState state = sprinting();
        state.onServerModifier(null);
        state.onClientTick(true);
        // A mob hit or a weak hit never reaches Player#causeExtraKnockback's setSprinting(false).
        state.onAttack(ClientSprintState.AttackReset.NONE);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertFalse(state.modifier());
    }

    @Test
    void certainResetFollowsTheClientExactly() {
        ClientSprintState state = sprinting();
        state.onServerModifier(null);
        state.onClientTick(true);
        state.onAttack(ClientSprintState.AttackReset.CERTAIN);
        assertTrue(state.flagKnown());
        assertFalse(state.flag());
        // aiStep re-sprinted without a packet: setSprinting(true) re-added the modifier.
        state.onClientTick(true);
        assertTrue(state.modifier());
    }

    @Test
    void possibleResetMakesLaterAttacksUncertain() {
        ClientSprintState state = sprinting();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        assertFalse(state.flagKnown());
        state.onClientTick(true);
        assertTrue(state.flagKnown());
    }

    @Test
    void respawnFollowsKeepMask() {
        ClientSprintState keepAll = sprinting();
        keepAll.onRespawn(true, true);
        assertTrue(keepAll.flag());
        assertTrue(keepAll.modifier());

        ClientSprintState keepEntityData = sprinting();
        keepEntityData.onRespawn(true, false);
        assertTrue(keepEntityData.flag());
        assertFalse(keepEntityData.modifier());

        ClientSprintState keepAttributes = sprinting();
        keepAttributes.onRespawn(false, true);
        assertFalse(keepAttributes.flag());
        assertTrue(keepAttributes.modifier());

        ClientSprintState keepNothing = sprinting();
        keepNothing.onRespawn(false, false);
        assertFalse(keepNothing.flag());
        assertFalse(keepNothing.modifier());
    }
}
