package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsU;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * LocalPlayer#sendChanges sends PLAYER_INPUT only when the keys differ from lastSentInput. A login or respawn can
 * replace the LocalPlayer with one that starts from Input.EMPTY and sends the keys still held again.
 */
class BadPacketsURepeatedInputTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);

    @Test
    void theSameKeysTwiceInARowFlag() throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
            f.input(NONE);
            f.input(NONE);
            assertEquals(2, f.check.getViolations());
        }
    }

    @Test
    void changedKeysDoNotFlag() throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.input(FORWARD_JUMP);
            f.input(FORWARD);
            f.input(NONE);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void theFirstInputHasNoKnownPredecessor() throws Exception {
        try (var f = new Fixture()) {
            f.input(NONE);
            assertEquals(0, f.check.getViolations());
        }
    }

    @ParameterizedTest
    @ValueSource(bytes = {0, ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA})
    void anyRespawnAllowsTheHeldKeysOnce(byte dataToKeep) throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.network.respawn(dataToKeep);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aLoginAllowsTheHeldKeysOnce() throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.login();
            f.answerPings();
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
            f.input(FORWARD);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void noRespawnAllowsReleasedKeysAgain() throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.input(NONE);
            f.network.respawn((byte) 0);
            f.input(NONE);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aRepeatBeforeTheRespawnIsAnsweredComesFromTheNewPlayer() throws Exception {
        try (var f = new Fixture()) {
            f.input(FORWARD);
            f.sendRespawn();
            f.input(FORWARD);
            f.answerPings();
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void keysChangedBeforeAnUnansweredRespawnMayRepeatAfterIt() throws Exception {
        try (var f = new Fixture()) {
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
        try (var f = new Fixture()) {
            // A translator writes this client's input each ridden tick.
            JavaTickFixture.setField(CultPlayer.class, f.network.player, "resolvedClientVersion", ClientVersion.V_1_21);
            f.input(FORWARD);
            f.input(FORWARD);
            assertEquals(0, f.check.getViolations());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordConsumerServices services = new RecordConsumerServices();
        private final RecordReceiveFixture network = new RecordReceiveFixture(ProtocolVersion.V26_3);
        private final BadPacketsU check = network.player.checkManager.getCheck(BadPacketsU.class);

        private Fixture() throws Exception {
            services.useNetwork(network.manager);
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
