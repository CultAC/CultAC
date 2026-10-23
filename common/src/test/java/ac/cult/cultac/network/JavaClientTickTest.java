package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaClientTickTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    private static final RelativeFlag KEEP_DELTA = new RelativeFlag(
            RelativeFlag.DELTA_X.getMask() | RelativeFlag.DELTA_Y.getMask() | RelativeFlag.DELTA_Z.getMask());
    private static final Set<Relative> ALL_DELTAS = Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void aVelocityReceivedWhileTheLevelLoadsWaitsForTheFirstPlayerTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);
            f.player.packetStateData.playerLoadedIntoLevel = false;

            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            // ClientPacketListener#hasClientLoaded is false: the client sends tick ends but its player does not tick.
            f.tickEnd();
            f.tickEnd();

            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.5, 0.5), false));
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    @Test
    void aTickWithoutPositionAfterATeleportTravelsBackToTheLastSentPosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            double fall = (-0.0784 - 0.08) * 0.98;
            double next = (fall - 0.08) * 0.98;
            Vec3 target = new Vec3(0.5, FLOOR_Y + 6, 0.5);
            Vec3 sent = target.add(0, next, 0);
            f.place(target.add(0, -fall, 0), new Vec3(0, fall, 0));
            f.input(NONE);
            assertExact(f.tick(target, false));
            assertExact(f.tick(sent, false));

            // A setback one tick back keeps the delta; the velocity after it retraces the tick the client sent.
            int teleport = f.player.sendTrailingTransaction();
            int proof = f.velocity(new Vec3(0, next, 0));
            f.teleport(teleport, target, KEEP_DELTA);
            f.pongThrough(proof);
            PredictionResult beforeTick =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            // The client ends within 2.0E-4 of LocalPlayer#xLast and sends no position.
            f.tickEnd();
            PredictionResult unsent =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(unsent != beforeTick, "the tick end committed no prediction");
            assertExact(unsent);

            double twoTicks = (next - 0.08) * 0.98;
            assertExact(f.tick(sent.add(0, twoTicks, 0), false));
            assertExact(f.tick(sent.add(0, twoTicks + (twoTicks - 0.08) * 0.98, 0), false));
            assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    @Test
    void aTickWithoutPositionAfterAFarTeleportIsTestedInPlace() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);

            Vec3 far = new Vec3(1_000_000.5, FLOOR_Y, 1_000_000.5);
            f.teleport(far, new RelativeFlag(0));
            assertTimeout(Duration.ofSeconds(10), f::tickEnd);

            assertEquals(far.z, f.player.z, 1.0E-9);
            assertEquals(0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    @Test
    void aFarTeleportAcceptanceReachesPositionListenersAtItsTarget() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);
            DumbTimer dumbTimer = f.player.checkManager.getListener(DumbTimer.class);
            Field lastLongTeleport = DumbTimer.class.getDeclaredField("lastLongTeleport");
            lastLongTeleport.setAccessible(true);

            // 26.3 sends no PosRot after the acceptance; DumbTimer's long-teleport window still opens.
            Vec3 far = new Vec3(1_000.5, FLOOR_Y, 1_000.5);
            long beforeTeleport = System.nanoTime();
            f.teleport(far, new RelativeFlag(0));

            // Only DumbTimer#onPositionUpdate's long-teleport branch moves this past the construction time.
            assertTrue(lastLongTeleport.getDouble(dumbTimer) >= beforeTeleport);
            assertEquals(
                    far, f.player.getSetbackTeleportUtil().lastKnownGoodPosition.getPos());
            assertEquals(far.x, f.player.x, 0);
            assertEquals(far.z, f.player.packetStateData.clientSidePosition.z, 0);
        }
    }

    @Test
    void aTeleportAcceptanceKeepsTheServerOpenedInventoryOfItsTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);

            // The client handles an open screen and a teleport in the same packet phase; only tick end clears it.
            f.player.serverOpenedInventoryThisTick = true;
            f.teleport(new Vec3(2.5, FLOOR_Y, 2.5), new RelativeFlag(0));

            assertEquals(2.5, f.player.x, 0);
            assertTrue(f.player.serverOpenedInventoryThisTick);
        }
    }

    @Test
    void aVelocityKeptThroughARelativeDeltaTeleportMovesTheNextTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);

            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            f.teleport(new Vec3(0.5, FLOOR_Y + 2, 0.5), KEEP_DELTA);

            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 2.5, 0.5), false));
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    @Test
    void aVelocityKeptThroughATeleportAddingDeltaMovesByTheirSum() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.teleport(STANDING, new RelativeFlag(0));
            f.input(NONE);
            f.tick(STANDING, true);

            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            // PositionMoveRotation#calculateAbsolute adds the packet delta to the motion the client already holds.
            f.playerPosition(new Vec3(0.5, FLOOR_Y + 2, 0.5), new Vec3(0, 0.2, 0), ALL_DELTAS);

            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 2.7, 0.5), false));
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    @Test
    void aVelocityKeptThroughATeleportAddingDeltaMovesNoFurther() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.teleport(STANDING, new RelativeFlag(0));
            f.input(NONE);
            f.tick(STANDING, true);

            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            f.playerPosition(new Vec3(0.5, FLOOR_Y + 2, 0.5), new Vec3(0, 0.2, 0), ALL_DELTAS);

            PredictionResult beyond = f.tick(new Vec3(0.5, FLOOR_Y + 2.9, 0.5), false);
            assertRejected(beyond, "VerticalOffset");
        }
    }

    @Test
    void anExplosionKeptThroughATeleportAddingDeltaGetsTheDeltaOnce() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.teleport(STANDING, new RelativeFlag(0));
            f.input(NONE);
            f.tick(STANDING, true);

            f.explosion(new Vec3(0, 0.5, 0));
            f.playerPosition(new Vec3(0.5, FLOOR_Y + 2, 0.5), new Vec3(0, 0.2, 0), ALL_DELTAS);

            // The knockback adds to the standing tick's gravity, -0.08 * 0.98F.
            double rise = -0.08 * 0.98F + 0.5 + 0.2;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 2 + rise, 0.5), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void anExplosionAfterATeleportAddsToTheVelocityItKept() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.teleport(STANDING, new RelativeFlag(0));
            f.input(NONE);
            f.tick(STANDING, true);

            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            f.playerPosition(new Vec3(0.5, FLOOR_Y + 2, 0.5), new Vec3(0, 0.2, 0), ALL_DELTAS);
            f.explosion(new Vec3(0, 0.3, 0));

            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 3, 0.5), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aConfirmedVelocityStartsTheTickBeforeACandidateWithoutIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 high = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(high, new Vec3(0, -0.1, 0));
            f.input(NONE);

            // The velocity is within the offset Simulation tolerates of the motion the client held.
            f.pongThrough(f.velocity(new Vec3(0, -0.1005, 0)));
            Vec3 velocity = f.player.checkManager.getKnockbackHandler().lastSent.getVel();

            assertExact(f.tick(high.add(velocity), false));
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    @Test
    void aConfirmedVelocityIsCarriedWhenAMoveWithoutItIsNearer() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 high = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(high, new Vec3(0, -0.1, 0));
            f.input(NONE);

            f.pongThrough(f.velocity(new Vec3(0, -0.1005, 0)));

            PredictionResult ignored = f.tick(high.add(0, -0.1, 0), false);
            assertTrue(ignored.getInitialStartingVel().isKnockback(), describe(ignored));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_1", "V_26_2", "V_26_3"})
    void aBlockAckAfterATeleportLeavesA26_1ClientWhereItIs(ClientVersion version) throws Exception {
        assertEquals(3.5, xAfterABlockAckFollowingATeleport(version), 0);
    }

    @Test
    void aBlockAckAfterATeleportSnapsBackA1_21_11Client() throws Exception {
        assertEquals(STANDING.x, xAfterABlockAckFollowingATeleport(ClientVersion.V_1_21_11), 0);
    }

    private static double xAfterABlockAckFollowingATeleport(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            f.tick(STANDING, true);
            CompensatedWorld world = f.player.compensatedWorld;
            BlockPos broken = new BlockPos(3, FLOOR_Y, 0);
            world.updateBlock(broken, BlockIds.STONE.defaultState());

            world.advanceClientPredictionSequence();
            world.startPredicting();
            world.updateBlock(broken, BlockIds.AIR.defaultState());
            world.stopPredicting(1);
            // BlockStatePredictionHandler#onTeleport records the sequence; the ack for it restores no position.
            Vec3 inside = new Vec3(3.5, FLOOR_Y, 0.5);
            f.teleport(inside, new RelativeFlag(0));
            world.handlePredictionConfirmation(1);
            f.pongThrough(f.player.lastTransactionSent.get());

            assertEquals(BlockIds.STONE.defaultState(), world.getBlockStateIdAt(broken));
            return f.player.x;
        }
    }

    @Test
    void aDoubleJumpThatLandsTheSameTickTravelsFlying() throws Exception {
        try (var f = flying(FLOOR_Y + 0.4, new Vec3(0, -0.6, 0), false)) {
            f.input(FORWARD_JUMP);
            f.abilities(true);
            f.abilities(false);
            PredictionResult landing = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + 0.05F * 0.98F), true);

            assertTrue(landing.isExempt(), "the landing tick travelled flying: " + describe(landing));
            assertFalse(f.player.isFlying);
        }
    }

    @Test
    void aFlightStopFollowedByALandingMoveTravelsFlying() throws Exception {
        try (var f = flying(FLOOR_Y + 0.4, new Vec3(0, -0.6, 0), true)) {
            f.input(FORWARD);
            f.abilities(false);
            PredictionResult landing = f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + 0.05F * 0.98F), true);

            assertTrue(landing.isExempt(), "the landing tick travelled flying: " + describe(landing));
            assertFalse(f.player.isFlying);
        }
    }

    @Test
    void aDoubleJumpStopInTheAirTravelsWalking() throws Exception {
        try (var f = flying(FLOOR_Y + 3, Vec3.ZERO, true)) {
            f.input(FORWARD_JUMP);
            f.abilities(false);
            PredictionResult stop = f.tick(new Vec3(0.5, FLOOR_Y + 3, 0.5 + 0.02F * 0.98F), false);

            assertFalse(f.player.isFlying);
            assertExact(stop);
        }
    }

    @Test
    void aLandingWithoutFlightIsUnchanged() throws Exception {
        try (var f = flying(FLOOR_Y + 0.4, new Vec3(0, -0.6, 0), false)) {
            f.input(FORWARD);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + 0.02F * 0.98F), true));
        }
    }

    @Test
    void aDeathEventForThePlayerLeavesItAlive() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, STANDING.add(3, 0, 0), 0, 0, 0);

            entityEvent(f, f.player.entityID, 3);
            entityEvent(f, 100, 3);
            f.pongThrough(f.player.lastTransactionSent.get());

            assertFalse(f.player.compensatedEntities.getSelf().isDead);
            assertTrue(f.player.compensatedEntities.getEntity(100).isDead);
        }
    }

    private static void entityEvent(JavaTickFixture f, int entityId, int status) {
        var packet = new ClientboundEntityEvent(entityId, (byte) status);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static JavaTickFixture flying(double y, Vec3 velocity, boolean flying) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.canFly = true;
        f.player.isFlying = flying;
        f.place(new Vec3(0.5, y, 0.5), velocity);
        return f;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
