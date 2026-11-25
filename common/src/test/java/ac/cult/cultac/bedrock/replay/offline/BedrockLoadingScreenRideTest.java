package ac.cult.cultac.bedrock.replay.offline;

import static org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType.START_LOADING_SCREEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A capture of a horse ridden into a nether portal: the rider held forward until the dimension change's loading screen
 * started, and every tick of the screen reported no input. On the first tick the client predicted the new horse,
 * still under the screen, it took the respawn teleport, reported no input and moved the horse 0.228 forward: the
 * first step of a horse that starts from rest with the rider holding forward.
 */
public class BedrockLoadingScreenRideTest {
    private static final int HORSE = 71;
    private static final Vec3 START = new Vec3(1.5, 64, 1.5);

    @Test
    public void theScreenHoldsTheRidersInputAndLetsTheHorseTravel() {
        double forward = travel(player -> {}, 1.0F, false);
        assertTrue(forward > 0.1);

        double held = travel(
                player -> {
                    player.bedrockState.offerAuthInputFrame(frame(player, 9, 1.0F, false, false));
                    var loading = player.bedrockState.loadingScreen;
                    loading.changeDimension(null, null);
                    assertTrue(loading.accept(START_LOADING_SCREEN, null));
                },
                0.0F,
                true);
        assertEquals(forward, held, 0);
    }

    /**
     * The respawn teleport was written before the link that seats the rider on the new horse, and the client handled
     * both, with the horse's chunk column, just before that first predicted tick. The tick from the seat answers the
     * teleport, and the horse travels although its column holds its blocks only from the next tick.
     */
    @Test
    public void theFirstTickFromTheSeatAnswersTheRespawnTeleportAndTravels() {
        double forward = travel(player -> {}, player -> {}, 1.0F, false);
        double seated = travel(
                player -> {
                    var teleports = player.getSetbackTeleportUtil().bedrockTeleports();
                    teleports.addImmediateBedrockTransportTeleport(START.add(0, 0.84375, 0), false);
                    player.lastTransactionReceived.set(player.lastTransactionSent.get());
                    var chunks = player.bedrockState.clientChunks;
                    chunks.publish((int) START.x, (int) START.z, 32);
                    chunks.receive(0, 0, 9);
                    teleports.captureBedrockVehicleMount(HORSE).run();
                    assertTrue(teleports.mustAcknowledgeBedrockTransportTeleport());
                },
                player -> assertFalse(
                        player.getSetbackTeleportUtil().bedrockTeleports().hasPendingBedrockTransportTeleport()),
                1.0F,
                true);
        assertEquals(forward, seated, 0);
    }

    private static double travel(Consumer<CultPlayer> before, float forward, boolean teleport) {
        return travel(before, player -> {}, forward, teleport);
    }

    /**
     * The z the horse ends its first predicted tick at, from rest, with {@code forward} reported on that tick, which
     * reports handling a teleport where {@code teleport}.
     */
    private static double travel(
            Consumer<CultPlayer> before, Consumer<CultPlayer> after, float forward, boolean teleport) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 8; z++)
                    player.compensatedWorld.updateBlock(
                            x,
                            63,
                            z,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:stone")
                                    .defaultState());
            player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, START, 0.0F, 0.0F, 0);
            var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.onGround = true;
            player.compensatedEntities.vehicles.setServerVehicle(
                    HORSE, new int[] {player.entityID}, player.lastTransactionSent.get());
            player.compensatedEntities.vehicles.applyVehiclePassengers(HORSE, new int[] {player.entityID});
            before.accept(player);
            BedrockFrameProcessor.process(
                    player, frame(player, 10, forward, true, teleport), BedrockPredictionTrigger.OFFLINE_REPLAY);
            assertSame(horse, player.bedrockState.predictedVehicle);
            after.accept(player);
            return BedrockProfileState.previousState(
                                    horse.bedrockPrediction.commit().carry())
                            .physicalFeetPosition()
                            .z()
                    - START.z;
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, long tick, float forward, boolean riding, boolean teleport) {
        var builder = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(START)
                .rotation(0, 0, 0)
                .moveVector(0, forward)
                .reportedEndOfTickVelocity(new Vec3(0, -0.0784, 0));
        if (!riding) return builder.build();
        return builder.predictedVehicleId((long) HORSE)
                .predictedVehicleJavaId(HORSE)
                .vehicleRotation(new BedrockAuthInputFrame.VehicleRotation(0, 0))
                .rawInputFlags(1L << PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE.ordinal()
                        | (teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0))
                .build();
    }
}
