package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client turns a flying happy ghast quickly, shifted by (-366, -20, 88) from the capture. Each tick
 * HappyGhast#tickRidden turns the root 8% toward the rider's yaw before LivingEntity#travel, and MoveVehicle reports
 * the turned root yaw; the turn is toward the preceding Rot, not toward that reported yaw.
 */
class JavaHappyGhastTurnTest {
    private static final int GHAST = 7119;
    private static final Vec3 SHIFT = new Vec3(-366, -20, 88);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @Test
    void aTurningRiderSteersTheGhastFromItsOwnYaw() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 before = at(366.7840228514798, 89.61860901882622, -88.00316491486508);
            Vec3 start = at(366.7051657738368, 89.6160598805815, -88.14411345665823);
            mount(f, start, -195.35394F, start.subtract(before).multiply(0.91F, 0.91F, 0.91F));

            Vec3 first = at(366.6304055712368, 89.6133846522057, -88.28812160465134);
            assertVehicleExact(ride(f, 221.69618F, 9.480405F, first, -190.78993F), 1);
            Vec3 second = at(366.5605551930719, 89.61070942382989, -88.43509379594434);
            assertVehicleExact(ride(f, 222.59619F, 9.480405F, second, -186.51904F), 2);
        }
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(x, y, z).add(SHIFT);
    }

    /** The harnessed adult ghast at {@code start} facing {@code yaw}, carrying {@code carried}, forward held. */
    private static void mount(JavaTickFixture f, Vec3 start, float yaw, Vec3 carried) {
        f.place(start, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, start, yaw, 0, 0);
        PacketEntityHappyGhast ghast = (PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(GHAST);
        ghast.hasBodyArmor = true;
        f.setPassengers(GHAST, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
    }

    /** One client tick: the rider's Rot, the ghast's move with its turned yaw and half the pitch, the tick end. */
    private static PredictionResult ride(JavaTickFixture f, float yaw, float pitch, Vec3 to, float ghastYaw) {
        PredictionResult previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), ghastYaw, pitch * 0.5F, false, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != previous, "the ghast move was not predicted");
        return result;
    }
}
