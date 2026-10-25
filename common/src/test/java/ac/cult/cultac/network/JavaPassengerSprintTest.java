package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A 26.3 passenger's sprint. LocalPlayer#isSprintingPossible needs LocalPlayer#vehicleCanSprint of a passenger, which is
 * Entity#canSprint (true only for Camel and CamelHusk among vehicles) on a vehicle the local player controls, and reads
 * the food level only of a player that is not a passenger. LocalPlayer#sendChanges sends the vehicle move before the
 * STOP_SPRINTING of the tick that stopped the sprint.
 */
class JavaPassengerSprintTest {
    private static final int VEHICLE = 100;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3[] NAUTILUS_RIDE = {
        at(341.933039011208, 62.75250756510613, -61.512551876154134),
        at(341.87043664710177, 62.75477098958726, -61.43417395472013)
    };

    /**
     * A player sprint-swimming at the surface mounts a saddled nautilus. AbstractNautilus has no canSprint, so the
     * first ride tick stops the sprint and its STOP_SPRINTING follows the vehicle move. Positions come from a 26.3
     * client's capture, moved next to the fixture's floor.
     */
    @Test
    void sprintSwimmingOntoANautilusIsNotSprintG() throws Exception {
        try (var f = pool();
                var ignored = observeOnly()) {
            SprintG sprintG = mountNautilus(f);

            rideTick(f, -369.95422F, NAUTILUS_RIDE[0], 34.39789F, true);
            assertEquals(0.0, sprintG.violations, "SprintG on the first ride tick");
            rideTick(f, -369.62418F, NAUTILUS_RIDE[1], 12.386856F, false);
            assertEquals(0.0, sprintG.violations, "SprintG on the second ride tick");
        }
    }

    @Test
    void aNautilusRiderThatKeepsSprintingIsSprintG() throws Exception {
        try (var f = pool();
                var ignored = observeOnly()) {
            SprintG sprintG = mountNautilus(f);

            rideTick(f, -369.95422F, NAUTILUS_RIDE[0], 34.39789F, false);
            assertEquals(0.0, sprintG.violations, "SprintG on the first ride tick");
            rideTick(f, -369.62418F, NAUTILUS_RIDE[1], 12.386856F, false);
            assertTrue(sprintG.violations > 0, "SprintG on the second ride tick");
        }
    }

    /** The last sprint-swimming tick before the mount, then the saddled nautilus's set_passengers. */
    private static SprintG mountNautilus(JavaTickFixture f) {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        Vec3 previous = at(341.60832155625894, 62.55424807562954, -64.11583502424588);
        Vec3 swim = at(341.6622503538886, 62.55424807562954, -63.93886690261666);
        f.place(previous, swim.subtract(previous));
        f.player.xRot = f.player.lastTickXRot = -370.02023F;
        f.player.yRot = f.player.lastTickYRot = -3.9948897F;
        f.player.pose = Pose.SWIMMING;
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
        f.player.sprintState.onClientTickEnd();
        f.input(FORWARD_SPRINT);
        Vec3 seat = at(341.994873046875, 62.750244140625, -61.564453125);
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), seat, 0, 78.75F, 0);
        var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
        nautilus.hasSaddle = true;
        nautilus.isTame = true;
        f.pongThrough(f.player.lastTransactionSent.get());
        PredictionResult swimTick = f.tick(swim, false);
        assertTrue(
                swimTick.getSimulationContext().getWorldData().getInWater().determinePessimistically(),
                describe(swimTick));

        Vec3 riderSeat = at(341.85004090365834, 63.29260225903804, -61.47112666406791);
        int trailing = f.startRiding(VEHICLE, 18, riderSeat, seat, 78.75F, 53.4375F);

        // ClientPacketListener#handleMovePlayer answers with the ID; handleMoveVehicle echoes the vehicle's position.
        f.pongThrough(trailing - 1);
        f.acceptTeleport(18);
        f.pongThrough(trailing);
        f.moveVehicle(seat, 78.75F, 53.4375F, false);
        f.pongThrough(f.player.lastTransactionSent.get());

        // The server mounts the player again a tick later and sends the nautilus's motion with it.
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.serverMoveVehicle(seat, 78.75F, 53.4375F);
        f.entityMotion(VEHICLE, Vec3.ZERO);
        f.entityMotion(VEHICLE, new Vec3(-0.043520722700360115, 0.0011597387535859927, 0.025148019288286694));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.moveVehicle(seat, 78.75F, 53.4375F, false);
        return sprintG;
    }

    /** A ride tick: the rider's Rot, the nautilus's move, then STOP_SPRINTING when {@code stop}. */
    private static PredictionResult rideTick(
            JavaTickFixture f, float yaw, Vec3 nautilus, float nautilusYaw, boolean stop) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, -3.9948897F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(nautilus, nautilusYaw, -1.9974449F, false);
        if (stop) {
            var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
        }
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    /** Water one block deep over the floor, its surface where the capture's was. */
    private static JavaTickFixture pool() throws Exception {
        var f = new JavaTickFixture();
        f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, FLOOR_Y, 8));
        return f;
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x - 342, y + 2, z + 62);
    }
}
