package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedCooldown;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleRespawn builds a new LocalPlayer. With KEEP_ENTITY_DATA it copies the old player's
 * synchronized data, pose and shared and living flags included; everything held on the player object itself, like
 * ItemCooldowns and autoSpinAttackTicks, starts fresh.
 */
class JavaRespawnKeptStateTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, -4.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final int KEEP_ALL =
            ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA;

    /**
     * A player gliding into a portal keeps the FALL_FLYING pose. Once the server clears fall flying,
     * LivingEntity#isVisuallySwimming makes LocalPlayer#isMovingSlowly crawl until Player#updatePlayerPose
     * stands it up at the end of the first tick.
     */
    @Test
    void aKeptGlidingPoseWithoutFallFlyingCrawlsTheFirstTick() throws Exception {
        try (var f = fixture()) {
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            assertEquals(Pose.FALL_FLYING, f.player.pose);

            respawn(f, KEEP_ALL);
            assertEquals(Pose.FALL_FLYING, f.player.pose);
            sendSharedFlags(f, (byte) 0);
            assertFalse(f.player.isGliding);
            load(f);

            double input = ClientMoveInput.of(f, FORWARD, 0.3F, 0.02F, 0.0F).z;
            PredictionResult result = f.tick(START.add(0, 0, input), true);
            assertTrue(exact(result), describe(result));
            assertEquals(Pose.STANDING, f.player.pose);
        }
    }

    @Test
    void aRespawnWithoutKeptDataStartsStanding() throws Exception {
        try (var f = fixture()) {
            f.player.isGliding = true;
            f.player.refreshPlayerPose();

            respawn(f, 0);
            assertFalse(f.player.isGliding);
            assertEquals(Pose.STANDING, f.player.pose);
        }
    }

    /** A player killed mid-riptide gets no living-flags update: the server's new player already has default flags. */
    @Test
    void aDeathRespawnDropsTheRiptidePoseAndSpin() throws Exception {
        try (var f = fixture()) {
            spin(f);
            respawn(f, 0);
            assertFalse(f.player.isRiptidePose);
            assertEquals(0, f.player.riptideSpinAttackTicks);
            assertFalse(f.player.riptideSpinCertain);
            assertEquals(Pose.STANDING, f.player.pose);
        }
    }

    @Test
    void aKeptDataRespawnKeepsTheRiptideFlagButNotTheSpin() throws Exception {
        try (var f = fixture()) {
            spin(f);
            respawn(f, KEEP_ALL);
            assertTrue(f.player.isRiptidePose);
            assertEquals(0, f.player.riptideSpinAttackTicks);
            assertFalse(f.player.riptideSpinCertain);
            assertEquals(Pose.SPIN_ATTACK, f.player.pose);
        }
    }

    /** LivingEntity only checks the bed on the server; the copied sleeping position keeps the client asleep. */
    @Test
    void sleepingSurvivesOnlyARespawnKeepingEntityData() throws Exception {
        try (var kept = fixture();
                var reset = fixture()) {
            kept.player.isInBed = true;
            respawn(kept, KEEP_ALL);
            assertTrue(kept.player.isInBed);

            reset.player.isInBed = true;
            respawn(reset, 0);
            assertFalse(reset.player.isInBed);
        }
    }

    @Test
    void aRespawnClearsItemCooldowns() throws Exception {
        try (var f = fixture()) {
            var pearl = OfflineCultTestBootstrap.item("minecraft:ender_pearl", 1);
            CompensatedCooldown cooldowns = f.player.checkManager.getCompensatedCooldown();
            cooldowns.addCooldown(CompensatedCooldown.group(pearl), 20, f.player.lastTransactionReceived.get());
            cooldowns.addPossibleCooldown("minecraft:chorus_fruit", 20);
            assertTrue(cooldowns.hasItem(pearl));

            respawn(f, KEEP_ALL);
            assertFalse(cooldowns.hasItem(pearl));
            assertFalse(cooldowns.possiblyHasItem(OfflineCultTestBootstrap.item("minecraft:chorus_fruit", 1)));
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        f.player.entityID = 7;
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(START, GROUNDED);
        f.input(FORWARD);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }

    private static void spin(JavaTickFixture f) {
        f.player.isRiptidePose = true;
        f.player.riptideSpinAttackTicks = 15;
        f.player.riptideSpinCertain = true;
        f.player.refreshPlayerPose();
        assertEquals(Pose.SPIN_ATTACK, f.player.pose);
    }

    /** A respawn into the same dimension, answered by the client. */
    private static void respawn(JavaTickFixture f, int dataToKeep) {
        DeadPlayerSetbackTest.respawn(
                f, dataToKeep, f.player.compensatedWorld.getLastClientboundDimension().dimension());
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The server places the respawned player, keeping its velocity, and the client finishes loading. */
    private static void load(JavaTickFixture f) {
        f.playerPosition(START, Vec3.ZERO, Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z));
        f.player.packetStateData.playerLoadedIntoLevel = true;
    }

    private static void sendSharedFlags(JavaTickFixture f, byte flags) {
        CultPlayer player = f.player;
        var packet = new EntityMetadata(
                player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
