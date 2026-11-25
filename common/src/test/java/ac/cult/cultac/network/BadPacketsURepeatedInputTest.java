package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsU;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LocalPlayer#sendChanges sends PLAYER_INPUT only when the keys differ from lastSentInput. A login or respawn can
 * replace the LocalPlayer with one that starts from Input.EMPTY and sends the keys still held again.
 */
class BadPacketsURepeatedInputTest {
    private static final String RELEASES = "ac.cult.cultac.network.JavaPistonReleaseCases#releases";
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theSameKeysTwiceInARowFlag(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
            f.input(NONE);
            f.input(NONE);
            assertEquals(2, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void changedKeysDoNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.input(FORWARD_JUMP);
            f.input(FORWARD);
            f.input(NONE);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theFirstInputHasNoKnownPredecessor(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(NONE);
            assertEquals(0, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0} keep {2}")
    @MethodSource("releasesAndKeptData")
    void anyRespawnAllowsTheHeldKeysOnce(String release, ClientVersion version, byte dataToKeep) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.network.respawn(dataToKeep);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aLoginAllowsTheHeldKeysOnce(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.login();
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void noRespawnAllowsReleasedKeysAgain(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.input(NONE);
            f.network.respawn((byte) 0);
            f.input(NONE);
            assertEquals(1, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aRepeatBeforeTheRespawnIsAnsweredComesFromTheNewPlayer(String release, ClientVersion version)
            throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.sendRespawn();
            f.input(FORWARD);
            f.answerPings();
            assertEquals(0, f.check.getViolations());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void keysChangedBeforeAnUnansweredRespawnMayRepeatAfterIt(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.sendRespawn();
            // The old LocalPlayer's change, then the new one's first input for the same keys.
            f.input(FORWARD_JUMP);
            f.answerPings();
            f.input(FORWARD_JUMP);
            assertEquals(0, f.check.getViolations());
            f.input(FORWARD_JUMP);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aClientWithoutTheKeyChangeRuleIsNotChecked() throws Exception {
        try (var f = new Fixture(ClientVersion.V_26_3)) {
            // A translator writes this client's input each ridden tick.
            JavaTickFixture.setField(CultPlayer.class, f.network.player, "resolvedClientVersion", ClientVersion.V_1_21);
            f.input(FORWARD);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
        }
    }

    /** The respawn's proof pong arrives after a repeat the new LocalPlayer may have sent; then the rule is back. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aRepeatBeforeTheProofPongIsAllowedThenTheRuleReturns(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.sendRespawn();
            f.input(FORWARD);
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations(), release);
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations(), release);
        }
    }

    /** Two respawns in flight: only the later one's pong proves the last LocalPlayer replacement. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void twoUnansweredRespawnsAllowTheHeldKeysUntilTheLastIsAnswered(String release, ClientVersion version)
            throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.sendRespawn();
            f.sendRespawn();
            f.input(FORWARD);
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations(), release);
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations(), release);
        }
    }

    /** Keys released before a respawn: the new LocalPlayer sends nothing until a key changes, however late. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void releasedKeysThenARespawnThenAKeyLaterDoNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.input(NONE);
            f.network.respawn((byte) 0);
            for (int i = 0; i < 40; i++) f.network.player.sendTransaction();
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations(), release);
        }
    }

    /** An answered respawn grants one repeat of the held keys, not of the next keys pressed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anAnsweredRespawnDoesNotExcuseALaterRepeat(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.network.respawn((byte) 0);
            f.input(FORWARD_JUMP);
            assertEquals(0, f.check.getViolations(), release);
            f.input(FORWARD_JUMP);
            assertEquals(1, f.check.getViolations(), release);
        }
    }

    /** ClientPacketListener#handleConfigurationStart clears the LocalPlayer; the next login creates a new one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void aServerSwitchThroughConfigurationAllowsTheHeldKeys(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            f.input(FORWARD);
            f.network.phase(ConnectionPhase.CONFIGURATION);
            f.network.phase(ConnectionPhase.PLAY);
            f.login();
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations(), release);
        }
    }

    /** LocalPlayer#sendChanges stored the keys as sent whatever the proxy did with the packet. */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void anInputAnEarlierHandlerCancelledStillCountsAsSent(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            var cancelled = new PacketReceiveEvent<>(
                    f.network.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_INPUT, FORWARD);
            cancelled.setCancelled(true);
            f.network.player.checkManager.dispatchEarlyReceive(cancelled);
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations(), release);
        }
    }

    /**
     * Through the real send route the respawn's proof is a ping written after the RESPAWN frame: a pong for a ping
     * written before it does not end the window, the pings after it do.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource(RELEASES)
    void theRespawnProofIsAPingWrittenAfterTheRespawnFrame(String release, ClientVersion version) throws Exception {
        try (var f = new Fixture(version)) {
            var network = f.network;
            network.registrar.registerOwn(PacketManager::registerListeners);
            f.input(FORWARD);
            network.player.sendTransaction();
            network.channel.flush();
            network.channel.runPendingTasks();
            assertTrue(network.channel.writeAndFlush(f.respawnFrame()).isSuccess());
            network.channel.runPendingTasks();
            int respawnType = network.data
                    .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                    .id("minecraft:respawn");
            int pingType = network.data
                    .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                    .id("minecraft:ping");
            var before = new ArrayList<Integer>();
            var after = new ArrayList<Integer>();
            boolean respawnSeen = false;
            Object written;
            while ((written = network.channel.readOutbound()) != null) {
                ByteBuf frame = (ByteBuf) written;
                try {
                    int type = Wire.readVarInt(frame);
                    if (type == respawnType) respawnSeen = true;
                    else if (type == pingType) (respawnSeen ? after : before).add(frame.readInt());
                } finally {
                    frame.release();
                }
            }
            assertTrue(respawnSeen, "the respawn reached the client");
            assertFalse(after.isEmpty(), "no ping after the respawn");
            for (int id : before) pong(network, id);
            assertTrue(
                    network.player.lastTransactionReceived.get() < replacementProof(f.check),
                    "a ping before the respawn frame proved the replacement");
            for (int id : after) pong(network, id);
            assertTrue(
                    network.player.lastTransactionReceived.get() >= replacementProof(f.check),
                    "the pings written after the respawn frame did not prove the replacement");
        }
    }

    @Test
    void aBedrockPlayerIsNotChecked() throws Exception {
        try (var f = new Fixture(ClientVersion.V_26_3)) {
            f.network.player.movementPlatform = MovementPlatform.BEDROCK;
            f.input(FORWARD);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
        }
    }

    static Stream<Arguments> releasesAndKeptData() {
        byte all = ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA;
        return JavaPistonReleaseCases.releases()
                .flatMap(release ->
                        Stream.of((byte) 0, all).map(keep -> Arguments.of(release.get()[0], release.get()[1], keep)));
    }

    private static void pong(RecordReceiveFixture network, int id) {
        network.channel.writeInbound(network.pong(id));
        ByteBuf forwarded = network.channel.readInbound();
        if (forwarded != null) forwarded.release();
    }

    private static int replacementProof(BadPacketsU check) throws Exception {
        Field field = BadPacketsU.class.getDeclaredField("replacementProof");
        field.setAccessible(true);
        return field.getInt(check);
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordConsumerServices services = new RecordConsumerServices();
        private final RecordReceiveFixture network = new RecordReceiveFixture(ProtocolVersion.V26_3);
        private final BadPacketsU check = network.player.checkManager.getCheck(BadPacketsU.class);

        private Fixture(ClientVersion version) throws Exception {
            services.useNetwork(network.manager);
            JavaTickFixture.setField(CultPlayer.class, network.player, "resolvedClientVersion", version);
            network.user.bind(mock(PlatformPlayer.class));
            network.player.nettyScheduler.removeScheduler();
            network.player.setExperimentalChecks(true);
        }

        private void input(ServerboundPlayerInput keys) {
            // Through the production check manager, which proves the handler's registration.
            network.player.checkManager.dispatchEarlyReceive(
                    new PacketReceiveEvent<>(network.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_INPUT, keys));
        }

        /** A respawn the client has not answered yet. */
        private void sendRespawn() {
            var packet = new ClientboundRespawn(spawnInfo("minecraft:the_end"), (byte) 0);
            var event = new PacketSendEvent<>(
                    network.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
            new PacketPlayerRespawn().onRespawn(event, network.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        private void login() {
            var packet = new ClientboundLogin(network.player.entityID, false, spawnInfo("minecraft:overworld"));
            var event =
                    new PacketSendEvent<>(network.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, packet, false);
            new PacketPlayerRespawn().onLogin(event, network.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        /** A 26.3 RESPAWN frame into the current level with the End's dimension type, keeping nothing. */
        private ByteBuf respawnFrame() {
            var info = spawnInfo("minecraft:the_end");
            ByteBuf frame = network.id(ClientboundPackets.RESPAWN, "respawn");
            Wire.writeVarInt(frame, info.dimensionTypeId());
            Wire.writeString(frame, info.dimension(), 32767);
            // Seed, VarInt game mode and previous game mode, debug, flat, no last death.
            frame.writeLong(0);
            Wire.writeVarInt(frame, 0);
            Wire.writeVarInt(frame, 0);
            frame.writeBoolean(false).writeBoolean(false).writeBoolean(false);
            Wire.writeVarInt(frame, 0);
            Wire.writeVarInt(frame, 63);
            return frame.writeByte(0);
        }

        private PlayerSpawnInfo spawnInfo(String dimensionType) {
            return new PlayerSpawnInfo(
                    ModelRegistryNamesState.defaults().id("minecraft:dimension_type", dimensionType),
                    network.player.compensatedWorld.getLastClientboundDimension().dimension(),
                    GameMode.SURVIVAL);
        }

        private void answerPings() {
            var player = network.player;
            for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
        }

        @Override
        public void close() throws Exception {
            network.close();
            services.close();
        }
    }
}
