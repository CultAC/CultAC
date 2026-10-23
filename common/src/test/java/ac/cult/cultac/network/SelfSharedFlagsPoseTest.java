package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfSharedFlagsPoseTest {
    @Test
    void sharedFlagsKeepTheLocalPoseUntilTheTickEnds() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var start = new Vec3(4.5, 64.0, 4.5);
            prepare(player, start);
            // A ceiling at 65.5 fits the crouching box but not the standing one, over every closest-space neighbour.
            var registry = DataTables.defaults().registry();
            int stone = registry.block("minecraft:stone").defaultState();
            int topSlab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "top");
            for (int x = 3; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), stone);
                    player.compensatedWorld.updateBlock(new BlockPos(x, 65, z), topSlab);
                }
            }
            for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertEquals(Pose.STANDING, player.pose);

            sendSharedFlags(player, (byte) 0);
            assertEquals(Pose.STANDING, player.pose);
            assertEquals(Pose.STANDING.height, player.boundingBox.maxY - player.boundingBox.minY, 1.0E-9);

            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(Vec3.ZERO);
            runner.callPrediction(start, start, true, 0, 0);
            assertEquals(Pose.CROUCHING, player.pose);
        }
    }

    /**
     * Player#getDesiredPose reads the living flags' spin bit only in Player#updatePlayerPose, after LocalPlayer#aiStep.
     * Living flags answered between a teleport into a ceiling and the next tick keep the standing box, so the tick's
     * LocalPlayer#moveTowardsClosestSpace still pushes the client out.
     */
    @Test
    void livingFlagsAfterATeleportKeepTheStandingBoxForTheClosestSpacePush() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.entityID = 7;
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, JavaTickFixture.FLOOR_Y + 7, 0), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var air = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 2, 0.5);
            f.place(air, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            assertExact(f.tick(air, false));

            // Standing at y 70 meets the stone at y 71; swimming fits. Every corner sits in the stone's column.
            var target = new Vec3(0.4, JavaTickFixture.FLOOR_Y + 6, 0.5);
            f.teleport(f.player.sendTrailingTransaction(), target, new RelativeFlag(0), 0, 0);
            sendLivingFlags(f, (byte) 0);

            // The west-first corners push X to -0.1; the east corners push Z north, then south to +0.1.
            assertExact(f.tick(target.add(new Vec3(-0.1, 0, 0.1)), false));
            assertEquals(Pose.SWIMMING, f.player.pose);
        }
    }

    private static void sendLivingFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.LIVING_ENTITY_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
    }

    private static void sendSharedFlags(CultPlayer player, byte flags) {
        var packet = new EntityMetadata(
                player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        int proof = player.lastTransactionSent.get();
        player.lastTransactionReceived.set(proof);
        player.latencyUtils.handleNettySyncTransaction(proof);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static void prepare(CultPlayer player, Vec3 start) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
        }
        player.entityID = 7;
        player.gamemode = GameMode.SURVIVAL;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.checkManager.getSimulationProcessor().handleRespawn();
        player.x = player.lastX = start.x;
        player.y = player.lastY = start.y;
        player.z = player.lastZ = start.z;
        player.onGround = true;
        player.refreshPlayerPose();
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
    }
}
