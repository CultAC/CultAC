package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HappyGhast#tickRidden and AbstractNautilus#tickRidden turn the root 8% and 50% of the way from its yaw toward the
 * rider's before it travels, and ServerboundMoveVehiclePacket#fromEntity reports the turned yaw. The root travels
 * along that yaw, and the server receives it.
 */
class RiddenRootTurnTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SKY = new Vec3(0.5, FLOOR_Y + 30, 0.5);
    private static final Vec3 UNDER_WATER = new Vec3(0.5, FLOOR_Y + 1, 0.5);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** One client tick: the rider's Rot yaw and the root yaw its MoveVehicle reports. */
    private record Tick(float riderYaw, float rootYaw) {}

    /** Mth#wrapDegrees(float). */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    /** The tickRidden turn, stored through Entity#setRot. */
    private static float turn(float rootYaw, float riderYaw, float fraction) {
        return (rootYaw + wrapDegrees(riderYaw - rootYaw) * fraction) % 360.0F;
    }

    private static JavaTickFixture ghast() throws Exception {
        return ghast(0.0F, FORWARD);
    }

    private static JavaTickFixture ghast(float spawnYaw, ServerboundPlayerInput input) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(SKY, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.HAPPY_GHAST, SKY, spawnYaw, 0, 0);
        ((PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(VEHICLE)).hasBodyArmor = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.pongThrough(f.player.sendTrailingTransaction());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(input);
        f.tickEnd();
        return f;
    }

    private static JavaTickFixture nautilus() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y + 3, 9));
        f.player.xRot = f.player.lastTickXRot = 0.0F;
        f.player.yRot = f.player.lastTickYRot = 0.0F;
        f.place(UNDER_WATER.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), UNDER_WATER, 0, 0, 0);
        PacketEntityNautilus nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
        nautilus.hasSaddle = true;
        nautilus.isTame = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(IDLE);
        f.player.boatData.useNextInputForBoatTick();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        return f;
    }

    private static PacketReceiveEvent<ServerboundMoveVehicle> tick(JavaTickFixture f, Vec3 to, Tick t) {
        var rot = new ServerboundMovePlayer(0, 0, 0, t.riderYaw(), 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        PacketReceiveEvent<ServerboundMoveVehicle> move = f.moveVehicle(to, t.rootYaw(), 0, false);
        f.tickEnd();
        return move;
    }

    private static PredictionResult last(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static Vec3 forward(float yaw, double length) {
        double rad = Math.toRadians(yaw);
        return new Vec3(-Math.sin(rad) * length, 0, Math.cos(rad) * length);
    }

    /** The positions the engine accepts for {@code ticks}, each tick aimed far along its reported yaw. */
    private static List<Vec3> ghastPath(List<Tick> ticks) throws Exception {
        return ghastPath(0.0F, ticks);
    }

    private static List<Vec3> ghastPath(float spawnYaw, List<Tick> ticks) throws Exception {
        List<Vec3> positions = new ArrayList<>();
        positions.add(SKY);
        for (int i = 0; i < ticks.size(); i++) {
            try (JavaTickFixture f = ghast(spawnYaw, FORWARD)) {
                for (int j = 0; j < i; j++) tick(f, positions.get(j + 1), ticks.get(j));
                tick(f, positions.get(i).add(forward(ticks.get(i).rootYaw(), 5)), ticks.get(i));
                positions.add(positions.get(i).add(last(f).getAcceptedClosestToTarget()));
            }
        }
        return positions;
    }

    private static void assertExact(PredictionResult r, String at) {
        assertTrue(r.getFlags().isEmpty() && !r.isExempt() && r.getOffset() < 1.0E-5, at + " " + describe(r));
    }

    @Test
    void aGhastTurningTowardItsRiderReportsItsYawUnchanged() throws Exception {
        List<Tick> ticks = new ArrayList<>();
        float rootYaw = 0;
        for (int i = 0; i < 2; i++) ticks.add(new Tick(0, 0));
        for (int i = 0; i < 8; i++) {
            rootYaw = turn(rootYaw, 90, 0.08F);
            ticks.add(new Tick(90, rootYaw));
        }
        List<Vec3> path = ghastPath(ticks);
        try (JavaTickFixture f = ghast()) {
            for (int i = 0; i < ticks.size(); i++) {
                PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, path.get(i + 1), ticks.get(i));
                assertExact(last(f), "tick " + i);
                assertEquals(ticks.get(i).rootYaw(), move.getPacket().yaw(), "tick " + i);
            }
        }
    }

    @Test
    void aGhastYawReportedPastItsTurnReachesTheServerAsTheTurnedYaw() throws Exception {
        List<Tick> ticks = List.of(new Tick(0, 0), new Tick(0, 0), new Tick(180, 180), new Tick(180, 180));
        List<Vec3> path = ghastPath(ticks);
        float first = turn(0, 180, 0.08F);
        float second = turn(first, 180, 0.08F);
        try (JavaTickFixture f = ghast()) {
            f.player.checkManager.getCheck(VehicleOffset.class).setEnabled(true);
            for (int i = 0; i < 2; i++) tick(f, path.get(i + 1), ticks.get(i));
            assertEquals(first, tick(f, path.get(3), ticks.get(2)).getPacket().yaw());
            assertEquals(second, tick(f, path.get(4), ticks.get(3)).getPacket().yaw());
            assertEquals(second, f.player.compensatedEntities.getEntity(VEHICLE).clientPhysicalYaw);
        }
    }

    @Test
    void aGhastStepAlongAYawPastItsTurnIsRejected() throws Exception {
        // Past the client ticks after a mount in which a control switch may still be settling.
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < 5; i++) ticks.add(new Tick(0, 0));
        ticks.add(new Tick(180, 180));
        ticks.add(new Tick(180, 180));
        List<Vec3> path = ghastPath(ticks);
        int last = ticks.size() - 1;
        try (JavaTickFixture f = ghast()) {
            for (int i = 0; i < last; i++) tick(f, path.get(i + 1), ticks.get(i));
            Vec3 accepted = path.get(last + 1).subtract(path.get(last));
            Vec3 reversed = new Vec3(accepted.x, accepted.y, -accepted.z);
            tick(f, path.get(last).add(reversed), ticks.get(last));
            JavaTickFixture.assertRejected(last(f), "VehicleOffset");
        }
    }

    @Test
    void aNautilusTurnsHalfwayTowardItsRider() throws Exception {
        try (JavaTickFixture f = nautilus()) {
            float rootYaw = 0;
            for (int i = 0; i < 6; i++) {
                rootYaw = turn(rootYaw, 90, 0.5F);
                PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, UNDER_WATER, new Tick(90, rootYaw));
                JavaTickFixture.assertVehicleExact(last(f), i);
                assertEquals(rootYaw, move.getPacket().yaw(), "tick " + i);
            }
        }
    }

    @Test
    void aNautilusYawReportedPastItsTurnReachesTheServerAsTheTurnedYaw() throws Exception {
        try (JavaTickFixture f = nautilus()) {
            f.player.checkManager.getCheck(VehicleOffset.class).setEnabled(true);
            PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, UNDER_WATER, new Tick(90, 90));
            JavaTickFixture.assertVehicleExact(last(f), 0);
            assertEquals(45.0F, move.getPacket().yaw());
            assertEquals(45.0F, f.player.compensatedEntities.getEntity(VEHICLE).clientPhysicalYaw);
        }
    }

    /** VehicleOffset left unable to modify packets forwards the claim; the engine keeps the turned yaw. */
    @Test
    void aNautilusYawReportedPastItsTurnIsForwardedWhenPacketsAreNotModified() throws Exception {
        try (JavaTickFixture f = nautilus()) {
            PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, UNDER_WATER, new Tick(90, 90));
            JavaTickFixture.assertVehicleExact(last(f), 0);
            assertFalse(f.player.checkManager.getCheck(VehicleOffset.class).shouldModifyPackets());
            assertEquals(90.0F, move.getPacket().yaw());
            assertEquals(45.0F, f.player.compensatedEntities.getEntity(VEHICLE).clientPhysicalYaw);
        }
    }

    /** Ticks whose rider yaw steps by {@code step} per tick, each root yaw the vanilla turn of the one before. */
    private static List<Tick> circling(float rootYaw, float riderYaw, float step, int count, float fraction) {
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            riderYaw += step;
            rootYaw = turn(rootYaw, riderYaw, fraction);
            ticks.add(new Tick(riderYaw, rootYaw));
        }
        return ticks;
    }

    private static void assertCirclingUnchanged(JavaTickFixture f, List<Tick> ticks, Vec3 at) throws Exception {
        for (int i = 0; i < ticks.size(); i++) {
            if (i % 100 == 0) {
                // The fixture sends its ticks faster than the wall clock.
                f.resetTimerBalances();
                JavaTickFixture.setField(
                        AbstractTimerCheck.class,
                        f.player.checkManager.getCheck(VehicleTimer.class),
                        "timerBalanceRealTime",
                        System.nanoTime() - 10_000_000_000L);
            }
            PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, at, ticks.get(i));
            assertFalse(move.isCancelled(), "tick " + i);
            JavaTickFixture.assertVehicleExact(last(f), i);
            assertEquals(ticks.get(i).rootYaw(), move.getPacket().yaw(), "tick " + i);
            assertEquals(
                    ticks.get(i).rootYaw(), f.player.compensatedEntities.getEntity(VEHICLE).clientPhysicalYaw,
                    "tick " + i);
        }
    }

    @Test
    void aGhastCirclingSeveralTurnsEachWayReportsItsYawUnchanged() throws Exception {
        List<Tick> right = circling(0.0F, 0.0F, 12.0F, 100, 0.08F);
        Tick turned = right.getLast();
        List<Tick> left = circling(turned.rootYaw(), turned.riderYaw(), -12.0F, 220, 0.08F);
        List<Tick> ticks = new ArrayList<>(right);
        ticks.addAll(left);
        assertTrue(turned.riderYaw() > 1080.0F && left.getLast().riderYaw() < -1080.0F);
        try (JavaTickFixture f = ghast(0.0F, IDLE)) {
            assertCirclingUnchanged(f, ticks, SKY);
        }
    }

    @Test
    void aNautilusCirclingSeveralTurnsEachWayReportsItsYawUnchanged() throws Exception {
        List<Tick> right = circling(0.0F, 0.0F, 40.0F, 30, 0.5F);
        Tick turned = right.getLast();
        List<Tick> left = circling(turned.rootYaw(), turned.riderYaw(), -40.0F, 66, 0.5F);
        List<Tick> ticks = new ArrayList<>(right);
        ticks.addAll(left);
        assertTrue(turned.riderYaw() > 1080.0F && left.getLast().riderYaw() < -1080.0F);
        try (JavaTickFixture f = nautilus()) {
            assertCirclingUnchanged(f, ticks, UNDER_WATER);
        }
    }

    @Test
    void aGhastDrivenPastAFullTurnEachWayTravelsAlongItsWrappedYaw() throws Exception {
        for (float spawnYaw : new float[] {350.0F, -350.0F}) {
            float riderYaw = spawnYaw + Math.copySign(100.0F, spawnYaw);
            List<Tick> ticks = new ArrayList<>();
            ticks.add(new Tick(spawnYaw, spawnYaw));
            float rootYaw = spawnYaw;
            float unwrapped = spawnYaw;
            for (int i = 0; i < 5; i++) {
                rootYaw = turn(rootYaw, riderYaw, 0.08F);
                unwrapped += wrapDegrees(riderYaw - unwrapped) * 0.08F;
                ticks.add(new Tick(riderYaw, rootYaw));
            }
            assertTrue(Math.abs(unwrapped) > 360.0F && Math.abs(rootYaw) < 360.0F);
            List<Vec3> path = ghastPath(spawnYaw, ticks);
            try (JavaTickFixture f = ghast(spawnYaw, FORWARD)) {
                for (int i = 0; i < ticks.size(); i++) {
                    PacketReceiveEvent<ServerboundMoveVehicle> move = tick(f, path.get(i + 1), ticks.get(i));
                    assertExact(last(f), spawnYaw + " tick " + i);
                    assertEquals(ticks.get(i).rootYaw(), move.getPacket().yaw(), spawnYaw + " tick " + i);
                }
            }
        }
    }
}
