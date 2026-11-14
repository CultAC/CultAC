package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.ReportedKeyInputComparison;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The crouch fit queries that take the crouching block scan as already done answer as the full queries do wherever the
 * full crouching query holds, under generated ceilings and moving boats and shulkers.
 */
class CrouchFitScanReuseEquivalenceTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final int SLAB = BLOCKS.block("minecraft:stone_slab").defaultState();
    private static final int[] CEILINGS = {
        BLOCKS.block("minecraft:air").defaultState(),
        BLOCKS.block("minecraft:stone").defaultState(),
        SLAB,
        BLOCKS.with(SLAB, "type", "top"),
        BLOCKS.block("minecraft:oak_fence").defaultState(),
        BLOCKS.block("minecraft:snow").defaultState(),
    };
    private static final int[] ENTITY_TYPES = {EntityTypeIds.OAK_BOAT, EntityTypeIds.SHULKER, EntityTypeIds.OAK_BOAT};
    private static final int FIRST_ENTITY = 500;
    private static final int WORLDS = 400;
    private static final int POSITIONS = 60;
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @Test
    void reusedCrouchScanGivesTheFullQueriesAnswers() throws Exception {
        SplittableRandom random = new SplittableRandom(11);
        List<String> mismatches = new ArrayList<>();
        int crouchFitsByBlocks = 0, entityBlocksCrouch = 0, ceilingBlocksBand = 0, entityBlocksBand = 0, neither = 0;
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            for (int world = 0; world < WORLDS; world++) {
                int entities = generate(f, random, world);
                for (int p = 0; p < POSITIONS; p++) {
                    double x = random.nextDouble(-1.5, 2.5);
                    double y = FLOOR_Y + (random.nextInt(3) == 0 ? random.nextDouble(-0.2, 0.6) : 0.0);
                    double z = random.nextDouble(-1.5, 2.5);
                    if (!player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, x, y, z)) continue;
                    crouchFitsByBlocks++;
                    boolean wantCrouch = player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, x, y, z);
                    boolean wantOnly = player.mayFitOnlyCrouchingWhen(x, y, z);
                    boolean gotCrouch = player.crouchFitsGivenBlocksEmpty(x, y, z);
                    boolean gotOnly = player.mayFitOnlyCrouchingGivenCrouchMayFit(x, y, z);
                    if (wantCrouch != gotCrouch || wantOnly != gotOnly) {
                        mismatches.add(world + " " + x + " " + y + " " + z + ": crouch " + wantCrouch + "/" + gotCrouch
                                + " only " + wantOnly + "/" + gotOnly);
                    }
                    if (!wantCrouch) entityBlocksCrouch++;
                    if (!wantOnly) {
                        neither++;
                    } else if (bandHasBlocks(player, x, y, z)) {
                        ceilingBlocksBand++;
                    } else {
                        entityBlocksBand++;
                    }
                }
                for (int e = 0; e < entities; e++) player.compensatedEntities.removeEntity(FIRST_ENTITY + e);
            }
        }
        assertEquals(List.of(), mismatches);
        String counts = crouchFitsByBlocks + " fit, " + entityBlocksCrouch + " entity blocks crouching, "
                + ceilingBlocksBand + " ceiling blocks band, " + entityBlocksBand + " entity blocks band, " + neither
                + " neither";
        System.out.println(counts);
        assertTrue(entityBlocksCrouch > 0 && ceilingBlocksBand > 0 && entityBlocksBand > 0 && neither > 0, counts);
    }

    @Test
    void sneakingUnderGeneratedCeilingsGetsTheSameKeyInputOffset() throws Exception {
        SplittableRandom random = new SplittableRandom(13);
        ReportedKeyInputComparison comparison = new ReportedKeyInputComparison();
        List<String> mismatches = new ArrayList<>();
        for (int world = 0; world < 40; world++) {
            try (var f = new JavaTickFixture()) {
                generate(f, random, world);
                Vec3 at = new Vec3(random.nextDouble(-0.5, 1.5), FLOOR_Y, random.nextDouble(-0.5, 1.5));
                PredictionResult last = null;
                for (int tick = 0; tick < 4; tick++) {
                    boolean sneak = random.nextBoolean();
                    f.place(at, Vec3.ZERO);
                    setField(
                            SimulationProcessor.class,
                            f.player.checkManager.getSimulationProcessor(),
                            "lastSneaking",
                            random.nextBoolean());
                    ServerboundPlayerInput keys = sneak ? SNEAK_FORWARD : FORWARD;
                    f.input(keys);
                    Vec3 input = ClientMoveInput.of(f, keys, sneak ? 0.3F : 1.0F, 0.1F, f.player.xRot);
                    f.move(at.add(input.x, 0.0, input.z), true);
                    f.tickEnd();
                    PredictionResult result =
                            f.player.checkManager.getSimulationProcessor().getLastPrediction();
                    if (result == null) continue;
                    for (PredictionResult candidate : result.getAnyReality()) {
                        if (candidate.getValidMovements() == null) continue;
                        String mismatch = comparison.compare(f.player, candidate, last);
                        if (mismatch != null) mismatches.add(world + "/" + tick + ": " + mismatch);
                    }
                    last = result;
                }
            }
        }
        assertEquals(List.of(), mismatches);
        System.out.println(comparison.compared + " compared, " + comparison.decided + " decided");
        assertTrue(comparison.decided > 40, comparison.compared + " compared, " + comparison.decided + " decided");
    }

    /** Fills the ceiling cells over the floor and adds moving entities around it; returns how many entities. */
    private static int generate(JavaTickFixture f, SplittableRandom random, int world) {
        CultPlayer player = f.player;
        int density = random.nextInt(4);
        for (int x = -3; x <= 4; x++) {
            for (int z = -3; z <= 4; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                    int state = y == FLOOR_Y || random.nextInt(4) > density
                            ? CEILINGS[0]
                            : CEILINGS[random.nextInt(CEILINGS.length)];
                    player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
                }
            }
        }
        int entities = world % 5 == 0 ? 0 : random.nextInt(1, 4);
        for (int e = 0; e < entities; e++) {
            int type = ENTITY_TYPES[random.nextInt(ENTITY_TYPES.length)];
            Vec3 from = new Vec3(
                    random.nextDouble(-1.5, 2.5), FLOOR_Y + random.nextDouble(0.0, 2.2), random.nextDouble(-1.5, 2.5));
            player.compensatedEntities.addEntity(FIRST_ENTITY + e, type, from, 0, 0, 0);
            PacketEntity entity = player.compensatedEntities.getEntity(FIRST_ENTITY + e);
            for (int move = random.nextInt(3); move > 0; move--) {
                entity.onBundleFirstTransaction(
                        true,
                        true,
                        random.nextDouble(-0.8, 0.8),
                        random.nextDouble(-0.5, 0.5),
                        random.nextDouble(-0.8, 0.8),
                        player);
                entity.onMovement(player, true);
            }
        }
        return entities;
    }

    /** Whether blocks overlap the standing box above the crouching one. */
    private static boolean bandHasBlocks(CultPlayer player, double x, double y, double z) {
        double half = Pose.STANDING.width * player.getScale() / 2.0D;
        SimpleCollisionBox band = new SimpleCollisionBox(
                        x - half,
                        y + Pose.CROUCHING.height * player.getScale(),
                        z - half,
                        x + half,
                        y + Pose.STANDING.height * player.getScale(),
                        z + half)
                .expand(-1.0E-7D);
        return !Collisions.isEmpty(player, band, y);
    }
}
