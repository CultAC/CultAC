package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 client that stood on a bed walks off its edge. Every grounded tick Entity#move clips the descending
 * velocity at the bed's top (y 69.5625), BedBlock#bounceUp reverses it at 0.66F and LivingEntity#travelInAir applies
 * gravity, so the vertical velocity settles where {@code v = (-0.66F * v - 0.08) * 0.98F}, about -0.0476, and the
 * first airborne move falls by it. The moves, inputs and rotations are bundle 01a1b620's (seqs 64984 to 64993),
 * shifted by (-2683, 0, +1306); the bed and the floor beside it are its terrain.
 */
class JavaBedSettledWalkOffTest {
    private static final ServerboundPlayerInput RIGHT =
            new ServerboundPlayerInput(false, false, false, true, false, false, true);
    private static final ServerboundPlayerInput FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theFirstMoveOffTheBedFallsByTheSettledBounce() throws Exception {
        try (var f = standOnBed()) {
            walkToTheEdge(f);
            check(f, "64990", 571.8592F, 34.541008F, 2684.5560110134656, 69.51489251900475, -1306.068294061567, false);
            check(f, "64991", 569.4543F, 35.561188F, 2684.6206648413827, 69.38983718519547, -1306.1018161072193, false);
            check(f, "64992", 568.94415F, 35.524754F, 2684.6794998264827, 69.18888295415127, -1306.132321169642, false);
            check(f, "64993", 567.1587F, 33.812298F, 2684.733039664467, 69.0, -1306.1600807772465, true);
        }
    }

    /** Leaving the bed with the velocity of a single bounce from rest, -0.0277, is a fall vanilla does not make. */
    @Test
    void leavingTheBedWithAFreshBounceStillFlags() throws Exception {
        try (var f = standOnBed()) {
            walkToTheEdge(f);
            double freshBounce = (0.0784000015258789 * 0.66F - 0.08) * (double) 0.98F;
            f.player.xRot = 571.8592F;
            f.player.yRot = 34.541008F;
            PredictionResult result =
                    f.tick(bundle(2684.5560110134656, 69.5625 + freshBounce, -1306.068294061567), false);
            assertRejected(result, "VerticalOffset");
        }
    }

    /** Holding the bed's height past its edge still flags. */
    @Test
    void hoveringPastTheBedStillFlags() throws Exception {
        try (var f = standOnBed()) {
            walkToTheEdge(f);
            f.player.xRot = 571.8592F;
            f.player.yRot = 34.541008F;
            f.tick(bundle(2684.5560110134656, 69.5625, -1306.068294061567), false);
            f.player.xRot = 569.4543F;
            f.player.yRot = 35.561188F;
            PredictionResult result = f.tick(bundle(2684.6206648413827, 69.5625, -1306.1018161072193), false);
            assertRejected(result, "VerticalOffset");
        }
    }

    /** The bundle's floor (y 68), its bed at y 69 and the client standing still on it with the settled velocity. */
    private static JavaTickFixture standOnBed() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_11);
        var registry = DataTables.defaults().registry();
        int head = registry.with(
                registry.with(registry.block("minecraft:pink_bed").defaultState(), "facing", "north"), "part", "head");
        int foot = registry.with(head, "part", "foot");
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, 68, z), BlockIds.STONE.defaultState());
            }
        }
        f.player.compensatedWorld.updateBlock(new BlockPos(0, 69, 0), head);
        f.player.compensatedWorld.updateBlock(new BlockPos(0, 69, 1), foot);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

        double velocityY = -0.0784000015258789;
        for (int tick = 0; tick < 200; tick++) velocityY = settle(velocityY);
        f.place(bundle(2683.311284867775, 69.5625, -1306.0227762859731), new Vec3(0.0, velocityY, 0.0));
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager
                .getSimulationProcessor()
                .setLastOnGround(DesyncStatus.fromBoolean(true));
        return f;
    }

    /** Seqs 64984 to 64989: strafing, then sprinting forward across the bed to its edge. */
    private static void walkToTheEdge(JavaTickFixture f) throws Exception {
        f.input(RIGHT);
        check(f, "64984", 569.6365F, 30.278019F, 2683.396465266544, 69.5625, -1305.9743170151419, true);
        check(f, "64985", 572.5515F, 30.496635F, 2683.5255829179155, 69.5625, -1305.8951351082626, true);
        f.input(FORWARD_RIGHT);
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
        check(f, "64986", 575.9038F, 30.860996F, 2683.724446143178, 69.5625, -1305.872454890369, true);
        check(f, "64987", 577.835F, 31.00674F, 2683.962009297527, 69.5625, -1305.876294078783, true);
        check(f, "64988", 577.9079F, 31.00674F, 2684.22072274031, 69.5625, -1305.8944520917798, true);
        f.input(FORWARD);
        check(f, "64989", 576.41394F, 31.771898F, 2684.437597422976, 69.5625, -1306.0068983806905, true);
    }

    private static void check(
            JavaTickFixture f, String seq, float yaw, float pitch, double x, double y, double z, boolean onGround) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
        PredictionResult result = f.tick(bundle(x, y, z), onGround);
        assertTrue(
                !result.isExempt()
                        && result.getOffset() < 1.0E-5
                        && result.getFlags().stream()
                                .map(flag -> flag.getCheck().getCheckName())
                                .noneMatch(name -> name.equals("VerticalOffset") || name.equals("Simulation")),
                seq + ": " + describe(result));
    }

    /** BedBlock#bounceUp of a falling velocity, then LivingEntity#travelInAir's gravity and drag. */
    private static double settle(double velocityY) {
        return (-velocityY * 0.66F - 0.08) * (double) 0.98F;
    }

    /** A recorded position moved onto the fixture: the bed at x 2683, z -1306 becomes x 0, z 0. */
    private static Vec3 bundle(double x, double y, double z) {
        return new Vec3(x - 2683.0, y, z + 1306.0);
    }
}
