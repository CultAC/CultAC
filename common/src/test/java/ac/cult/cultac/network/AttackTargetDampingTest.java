package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SprintingAttacker.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#startAttack attacks only an entity Minecraft#pick hit, one hit result per tick, and a sprinting
 * full-strength hit on a player damps X/Z by 0.6. Attacks the client cannot have made leave that damping certain.
 */
class AttackTargetDampingTest {
    private static final int NEVER_SENT = 9999;
    private static final int SPAWNED = 300;

    @Test
    void aSprintHitOnAPlayerRequiresTheDamping() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(1, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void anAttackOnAnIdNeverSentLeavesTheNextHitCertain() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.attack(NEVER_SENT);
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(1, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void theDampedMoveAfterAnAttackOnAnIdNeverSentIsExact() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.attack(NEVER_SENT);
            s.attack(TARGET);
            PredictionResult r = s.move(0.6D);
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void anAttackOnAnUnpickableEntityLeavesTheNextHitCertain() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:item");
            s.pong();
            s.attack(SPAWNED);
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(1, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void anAttackOnAnUnpickableSpawnInFlightLeavesTheNextHitCertain() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:experience_orb");
            s.attack(SPAWNED);
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(1, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void aSwingBeforeAnAttackInTheSameTickIsNoMiss() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.swing();
            s.attack(TARGET);
            s.swing();
            PredictionResult r = s.move(1.0D);
            assertEquals(1, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(s.keepSprint() > 0, describe(r));
        }
    }

    @Test
    void aMissThenAHitInTheNextTickMayBeWeak() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.swing();
            assertTrue(exact(s.move(1.0D)));
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertEquals(1, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void aMissThenADampedHitInTheNextTickIsExact() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.swing();
            assertTrue(exact(s.move(1.0D)));
            s.attack(TARGET);
            PredictionResult r = s.move(0.6D);
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void aHitOnAPlayerSpawnInFlightMayOrMayNotDamp() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:player");
            s.attack(SPAWNED);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertEquals(1, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:player");
            s.attack(SPAWNED);
            PredictionResult r = s.move(0.6D);
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void aSpawnWhoseProofArrivesInABurstStaysPossibleUntilThen() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:player");
            for (int tick = 0; tick < 15; tick++) assertTrue(exact(s.move(1.0D)));
            s.attack(SPAWNED);
            PredictionResult r = s.move(1.0D);
            assertTrue(exact(r), describe(r));
            s.pong();
            assertEquals(0.0D, s.keepSprint(), describe(r));
            for (int tick = 0; tick < 12; tick++) assertTrue(exact(s.move(1.0D)));
            s.attack(SPAWNED);
            PredictionResult proven = s.move(1.0D);
            assertEquals(1, proven.getSimulationContext().getMinAttackSlow(), describe(proven));
        }
    }

    @Test
    void anEntityRemovedBeforeItsSpawnWasProvenMayStillBeHit() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:zombie");
            s.remove(SPAWNED);
            s.attack(SPAWNED);
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void anEntityWhoseRemovalIsInFlightResetsTheTicker() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:zombie");
            s.pong();
            s.remove(SPAWNED);
            s.attack(SPAWNED);
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMinAttackSlow(), describe(r));
            assertEquals(0, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            s.pong();
            assertTrue(s.f.player.compensatedEntities.unprovenEntityType(SPAWNED) < 0);
        }
    }

    @Test
    void aHitOnAMobLeavesTheNextTicksHitWeak() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:zombie");
            s.pong();
            s.attack(SPAWNED);
            assertTrue(exact(s.move(1.0D)));
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    @Test
    void aHitOnAnEnderDragonPartLeavesTheNextTicksHitWeak() throws Exception {
        try (var s = new SprintingAttacker(false)) {
            s.spawn(SPAWNED, "minecraft:ender_dragon");
            s.pong();
            s.attack(SPAWNED + 1);
            assertTrue(exact(s.move(1.0D)));
            s.attack(TARGET);
            PredictionResult r = s.move(1.0D);
            assertEquals(0, r.getSimulationContext().getMaxAttackSlow(), describe(r));
            assertTrue(exact(r), describe(r));
            assertEquals(0.0D, s.keepSprint(), describe(r));
        }
    }

    /** Cushion#hurtClient depends on isBreakingDeniedFor, so its hit may damp and leaves the next hit weak. */
    @Test
    void aHitOnACushionMayOrMayNotDamp() throws Exception {
        for (double damping : new double[] {1.0D, 0.6D}) {
            try (var s = new SprintingAttacker(false)) {
                s.spawn(SPAWNED, "minecraft:cushion");
                s.pong();
                s.attack(SPAWNED);
                s.attack(TARGET);
                PredictionResult r = s.move(damping);
                assertEquals(0, r.getSimulationContext().getMinAttackSlow(), describe(r));
                assertEquals(1, r.getSimulationContext().getMaxAttackSlow(), describe(r));
                assertTrue(exact(r), describe(r));
                assertEquals(0.0D, s.keepSprint(), describe(r));
            }
        }
    }

    @Test
    void pickableTypesFollowEntityIsPickable() {
        assertTrue(EntityTypeUtil.isPickable(EntityTypeIds.PLAYER));
        assertTrue(EntityTypeUtil.isPickable(EntityTypeIds.ARMOR_STAND));
        assertTrue(EntityTypeUtil.isPickable(EntityTypeIds.INTERACTION));
        assertTrue(EntityTypeUtil.isPickable(EntityTypeIds.WIND_CHARGE));
        assertFalse(EntityTypeUtil.isPickable(EntityTypeIds.ENDER_DRAGON));
        assertFalse(EntityTypeUtil.isPickable(EntityTypeIds.ITEM));
        assertFalse(EntityTypeUtil.isPickable(EntityTypeIds.EXPERIENCE_ORB));
    }
}
