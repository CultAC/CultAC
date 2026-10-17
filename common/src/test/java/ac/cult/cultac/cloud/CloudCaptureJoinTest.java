package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.CaptureFixture.Host;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Join;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/** What a JOIN says about where its segment starts and what the client was sent. */
class CloudCaptureJoinTest {
    private static final Map<String, List<String>> DAMAGE_TYPES =
            Map.of("minecraft:damage_type", List.of("minecraft:arrow", "minecraft:magic"));

    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    /** Installed while configuring, so the segment starts at the backend's login. */
    private static CaptureFixture atLogin() {
        CaptureFixture fixture = new CaptureFixture(Host.PROXY, false);
        CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
        fixture.phase(ConnectionPhase.CONFIGURATION);
        fixture.install();
        fixture.phase(ConnectionPhase.PLAY);
        fixture.capture.loginSent(
                new ClientboundLogin(7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL)));
        fixture.clientbound("login", (byte) 1);
        fixture.channel.runPendingTasks();
        return fixture;
    }

    private static List<Join> joins(FakeUplink socket) {
        return socket.of(IntakeMessage.KindCase.JOIN).stream()
                .map(IntakeMessage::getJoin)
                .toList();
    }

    @Test
    void aSegmentStartedAtALoginIsNotResumed() {
        try (CaptureFixture fixture = atLogin()) {
            Join join = joins(fixture.uplinks.socket("a")).get(0);
            assertFalse(join.getResumed());
            assertEquals(7, join.getEntityId());
        }
    }

    @Test
    void aSegmentStartedMidPlayIsResumed() {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            assertTrue(joins(fixture.uplinks.socket("a")).get(0).getResumed());
        }
    }

    @Test
    void aReconnectResumesASegmentThatStartedAtALogin() {
        try (CaptureFixture fixture = atLogin()) {
            FakeUplink a = fixture.uplinks.socket("a");
            UUID player = fixture.player.getUniqueId();
            a.captureAll(player);
            fixture.tickEnd();
            a.streams.get(player).disconnected(a.generation);
            a.generation++;
            a.streams.get(player).connected(a.generation);
            fixture.channel.runPendingTasks();
            List<Join> joins = joins(a);
            assertEquals(2, joins.size());
            assertFalse(joins.get(0).getResumed());
            assertTrue(joins.get(1).getResumed());
            assertEquals(7, joins.get(1).getEntityId(), "the login's facts still go");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {774, 777})
    void aClientGetsTheRegistriesAsItsOwnWireSentThem(int protocol) {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            Mockito.when(fixture.platform.getClientProtocol()).thenReturn(ProtocolVersion.of(protocol));
            fixture.receivedRegistries(ProtocolVersion.of(protocol), DAMAGE_TYPES);
            fixture.uplinks.terms = CloudConnection.Terms.sections(2, 2);
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            Join join = joins(fixture.uplinks.socket("a")).get(0);
            assertEquals(protocol, join.getClientProtocol());
            assertEquals(
                    DAMAGE_TYPES.get("minecraft:damage_type"),
                    join.getRegistriesMap().get("minecraft:damage_type").getEntriesList());
        }
    }

    @Test
    void aClientBehindATranslatorGetsNoRegistries() {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            Mockito.when(fixture.platform.getClientProtocol()).thenReturn(ProtocolVersion.of(769));
            fixture.receivedRegistries(ProtocolVersion.V26_3, DAMAGE_TYPES);
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            Join join = joins(fixture.uplinks.socket("a")).get(0);
            assertEquals(769, join.getClientProtocol());
            assertTrue(join.getRegistriesMap().isEmpty());
        }
    }

    @Test
    void noRegistriesAreSentBeforeTheClientReceivedAny() {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            assertTrue(
                    joins(fixture.uplinks.socket("a")).get(0).getRegistriesMap().isEmpty());
        }
    }
}
