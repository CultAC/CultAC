package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A client sprint-jumping into a ceiling over slime: under top-half trapdoors with 0.0125 of headroom, where the moves
 * are a real 26.1 client's and the 26.2 client's from vanilla's formulas, and on bottom-half trapdoors under slabs,
 * where they are a real 1.21.11 client's; all translated onto the fixture.
 */
class JavaSlimeTrapdoorHeadHitTest {
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final ServerboundPlayerInput FORWARD_LEFT_JUMP =
            new ServerboundPlayerInput(true, false, true, false, true, false, true);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /**
     * The box stays at Entity#getOnPosLegacy's slime, so SlimeBlock#updateEntityMovementAfterFallOn keeps the rising
     * velocity through every head hit until gravity turns it; the landing then bounces, and the grounded tick after it
     * scales the jump's velocity by SlimeBlock#stepOn.
     */
    @Test
    void theGroundTickAfterAHeadHitLandingCarriesStepOnIntoTheNextJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            start(f, (0.42F - 0.08) * (double) 0.98F);

            check(f, "T1", 269.47195455945223, 62.012500047683716, 149.36523198388656, false);
            f.input(FORWARD_LEFT);
            check(f, "T2", 269.5271308713248, 62.012500047683716, 149.5749733917055, false);
            f.input(FORWARD_JUMP);
            check(f, "T3", 269.5766549547544, 62.012500047683716, 149.7913088315119, false);
            check(f, "T4", 269.6210355101527, 62.012500047683716, 150.01364484059988, false);
            check(f, "T5", 269.6607354549076, 62.012500047683716, 150.24144136789144, false);
            check(f, "T6", 269.6961760438542, 62.0, 150.47420696689147, true);
            f.input(FORWARD);
            check(f, "T7", 269.7076574227699, 62.0, 150.61285017180182, true);
            f.input(FORWARD_JUMP);
            check(f, "J8", 269.70428929608687, 62.012500047683716, 150.90837217248153, false);
            check(f, "T9", 269.70115093788917, 62.012500047683716, 151.14898295541298, false);
            check(f, "T10", 269.6976086700255, 62.012500047683716, 151.39340952738135, false);
        }
    }

    /**
     * Entity#restituteMovementAfterCollisions zeroes y at each head hit, and at a landing slower than gravity: the
     * grounded tick after that landing scales the jump's velocity by SlimeBlock#stepOn from the zeroed y.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_2", "V_26_3"})
    void aSlowLandingZeroesTheVerticalVelocityBeforeStepOn(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            start(f, (0.0 - 0.08) * (double) 0.98F);

            check(f, "T1", 269.47195455945223, 62.0, 149.36523198388656, true);
            f.input(FORWARD_LEFT);
            check(f, "T2", 269.52517837716107, 62.0, 149.48438297775326, true);
            f.input(FORWARD_JUMP);
            check(f, "J3", 269.53444945555816, 62.012500047683716, 149.77409466806395, false);
            check(f, "T4", 269.5405124392253, 62.0, 150.01047554478632, true);
            check(f, "T5", 269.54135808211555, 62.0, 150.1536184379143, true);
            check(f, "T6", 269.54016619165475, 62.0, 150.25066301284747, true);
            f.input(FORWARD);
            check(f, "T7", 269.53835771291904, 62.0, 150.33375754250702, true);
            f.input(FORWARD_JUMP);
            check(f, "J8", 269.5309751896887, 62.012500047683716, 150.6125580037796, false);
            check(f, "T9", 269.52491435062467, 62.0, 150.84099550527316, true);
            check(f, "T10", 269.5211739295034, 62.0, 150.9811336672966, true);
        }
    }

    /**
     * A 1.21.11 client on bottom-half trapdoors over slime under slabs: Entity#getOnPosLegacy of the head hit is the
     * trapdoor, which zeroes y, and of the landing the slime, which bounces it. The bounce leaves a y under
     * LivingEntity#aiStep's 0.003 threshold, so the next move's zero y is no collision below, Entity#onGround drops and
     * Entity#applyEffectsFromBlocks skips SlimeBlock#stepOn. The moves are a real capture's.
     */
    @Test
    void aZeroYMoveOnTheTrapdoorLeavesTheGroundWithoutStepOn() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_11)) {
            var registry = DataTables.defaults().registry();
            int trapdoor = registry.block("minecraft:iron_trapdoor").defaultState();
            int slab = registry.with(
                    registry.block("minecraft:cobbled_deepslate_slab").defaultState(), "type", "bottom");
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), trapdoor);
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), slab);
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 landFrom = onTrapdoor(269.544388380157, 62.200000047683716, 135.67242006482167);
            Vec3 landTo = onTrapdoor(269.54245684321427, 62.1875, 135.440965736047);
            Vec3 landed = landTo.subtract(landFrom);
            double restY = (0.0784000015258789 - 0.08) * (double) 0.98F;
            double stepOn = 0.91F * (0.4 + Math.abs(restY) * 0.2);
            f.place(landTo, new Vec3(landed.x * stepOn, restY, landed.z * stepOn));
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(true));
            f.player.xRot = -180.4832F;
            f.player.yRot = 6.40206F;
            f.input(FORWARD);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            f.input(FORWARD_JUMP);
            checkOnTrapdoor(f, "J1", 269.53963672905314, 62.200000047683716, 135.10291225599644, false);
            checkOnTrapdoor(f, "T2", 269.53737115907944, 62.1875, 134.8313301951071, true);
            f.input(FORWARD);
            checkOnTrapdoor(f, "T3", 269.53609754637597, 62.1875, 134.67865180684205, false);
            f.input(FORWARD_JUMP);
            checkOnTrapdoor(f, "T4", 269.53495782953274, 62.1875, 134.54202282108156, true);
        }
    }

    private static void checkOnTrapdoor(
            JavaTickFixture f, String name, double x, double y, double z, boolean onGround) {
        PredictionResult result = f.tick(onTrapdoor(x, y, z), onGround);
        assertTrue(exact(result), name + ": " + describe(result));
    }

    /** A recorded position moved onto the fixture: the trapdoor block at 62 becomes FLOOR_Y. */
    private static Vec3 onTrapdoor(double x, double y, double z) {
        return new Vec3(x - 269.0, y - 62.0 + FLOOR_Y, z - 138.0);
    }

    /** Slime under trapdoors, and the client just after its sprint jump off the slime with {@code jumpY} carried. */
    private static void start(JavaTickFixture f, double jumpY) {
        var registry = DataTables.defaults().registry();
        int trapdoor = registry.with(registry.block("minecraft:oak_trapdoor").defaultState(), "half", "top");
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), trapdoor);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

        Vec3 jumpFrom = recorded(269.3992434403807, 62.0, 148.8933003526408);
        Vec3 jumpTo = recorded(269.4309727524378, 62.012500047683716, 149.15548685817885);
        Vec3 jumped = jumpTo.subtract(jumpFrom);
        double friction = 0.8F * 0.91F;
        f.place(jumpTo, new Vec3(jumped.x * friction, jumpY, jumped.z * friction));
        f.player.xRot = 1.5474854F;
        f.player.yRot = 9.881536F;
        f.input(FORWARD_LEFT_JUMP);
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
    }

    private static void check(JavaTickFixture f, String name, double x, double y, double z, boolean onGround) {
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), name + ": " + describe(result));
    }

    /** A recorded position moved onto the fixture: the slime top 62.0 becomes FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 269.0, y - 62.0 + FLOOR_Y, z - 151.0);
    }
}
