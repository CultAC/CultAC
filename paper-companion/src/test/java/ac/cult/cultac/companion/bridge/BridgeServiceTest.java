package ac.cult.cultac.companion.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeAuth;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Auth;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Challenge;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Close;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloudFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reject;
import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.companion.Stubs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeServiceTest {
    private static final byte[] SECRET = "forwarding-secret".getBytes(StandardCharsets.UTF_8);
    private static final UUID PLAYER = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final RecordingHandler handler = new RecordingHandler();
    private BridgeService service;
    private FakeEndpoint endpoint;

    @BeforeEach
    void setUp() {
        service = new BridgeService(SECRET, new InlinePlatform(), Logger.getLogger("bridge-test"), false);
        service.addHandler(handler);
        endpoint = new FakeEndpoint(PLAYER);
    }

    @Test
    void messagesBeforeReadyAreIgnored() {
        service.receive(endpoint, new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread"));
        assertTrue(endpoint.sent.isEmpty());

        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread"));
        assertEquals(1, endpoint.sent.size());
        assertInstanceOf(Challenge.class, endpoint.sent.get(0));
        assertTrue(handler.messages.isEmpty());
        assertNull(service.session(PLAYER));
    }

    @Test
    void authWithoutHelloIsIgnored() {
        service.receive(endpoint, new Auth(new byte[BridgeProtocol.HMAC_BYTES]));
        assertTrue(endpoint.sent.isEmpty());
        assertNull(service.anySession());
    }

    @Test
    void wrongHmacNeverReachesReady() {
        service.receive(endpoint, hello(nonce(1)));
        Challenge challenge = (Challenge) endpoint.sent.get(0);
        byte[] wrong = BridgeAuth.hmac(
                "other-secret".getBytes(StandardCharsets.UTF_8), PLAYER, nonce(1), challenge.backendNonce());
        service.receive(endpoint, new Auth(wrong));
        service.receive(endpoint, new Auth(hmac(nonce(1), challenge)));

        assertEquals(2, endpoint.sent.size());
        assertEquals(
                "auth failed",
                assertInstanceOf(Reject.class, endpoint.sent.get(1)).reason());
        assertNull(service.anySession());
        assertEquals(0, handler.ready);
        service.receive(endpoint, new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread"));
        assertTrue(handler.messages.isEmpty());
    }

    @Test
    void handshakeReachesReadyAndDispatches() {
        service.setListenerBits(0b101);
        service.receive(endpoint, hello(nonce(1)));
        Challenge challenge = (Challenge) endpoint.sent.get(0);
        assertEquals(BridgeProtocol.VERSION, challenge.version());
        service.receive(endpoint, new Auth(hmac(nonce(1), challenge)));

        Ready ready = assertInstanceOf(Ready.class, endpoint.sent.get(1));
        assertEquals(List.of(false, true), endpoint.readyWhenSent);
        assertEquals(BridgeService.SERVED, ready.capabilities());
        assertTrue(ServerCapability.has(ready.capabilities(), ServerCapability.TELEPORT));
        assertTrue(ServerCapability.has(ready.capabilities(), ServerCapability.CLEAR_ACTIVE_ITEM));
        assertEquals(0b101, ready.listeners());
        assertEquals(WORLD, ready.worldUid());

        CompanionSession session = service.session(PLAYER);
        assertNotNull(session);
        assertTrue(session.isReady());
        assertSame(session, service.anySession());
        assertEquals(1, handler.ready);

        ClearActiveItem action = new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread");
        service.receive(endpoint, action);
        assertEquals(List.of(action), handler.messages);

        service.endpointClosed(endpoint);
        assertFalse(session.isReady());
        assertNull(service.session(PLAYER));
        assertNull(service.anySession());
        assertEquals(1, handler.closed);
        session.send(new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread"));
        assertEquals(2, endpoint.sent.size());
    }

    @Test
    void readyCarriesTheBackendProof() {
        service.receive(endpoint, hello(nonce(1)));
        Challenge challenge = (Challenge) endpoint.sent.get(0);
        service.receive(endpoint, new Auth(hmac(nonce(1), challenge)));

        Ready ready = assertInstanceOf(Ready.class, endpoint.sent.get(1));
        assertArrayEquals(BridgeAuth.backendHmac(SECRET, PLAYER, challenge.backendNonce(), nonce(1)), ready.hmac());
        assertFalse(Arrays.equals(hmac(nonce(1), challenge), ready.hmac()));
    }

    @Test
    void readyRebuiltForNewListenersKeepsTheProof() {
        service.addHandler(new CompanionMessageHandler() {
            @Override
            public void onReady(CompanionSession session) {
                service.setListenerBits(0b1);
            }
        });
        service.receive(endpoint, hello(nonce(1)));
        Challenge challenge = (Challenge) endpoint.sent.get(0);
        service.receive(endpoint, new Auth(hmac(nonce(1), challenge)));

        Ready ready = assertInstanceOf(Ready.class, endpoint.sent.get(1));
        assertEquals(0b1, ready.listeners());
        assertArrayEquals(BridgeAuth.backendHmac(SECRET, PLAYER, challenge.backendNonce(), nonce(1)), ready.hmac());
    }

    @Test
    void closeFromTheProxyClosesTheSession() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));
        CompanionSession session = service.session(PLAYER);

        service.receive(endpoint, new Close("ready timeout"));

        assertFalse(session.isReady());
        assertNull(service.session(PLAYER));
        assertEquals(1, handler.closed);
        assertEquals(2, endpoint.sent.size());
    }

    @Test
    void closeAllRejectsEverySessionFirst() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));

        service.closeAll("companion disabled");

        assertEquals(new Reject("companion disabled"), endpoint.sent.get(2));
        assertNull(service.session(PLAYER));
        assertEquals(1, handler.closed);
    }

    @Test
    void authFailuresWarnOncePerConnectionAndMinute() {
        List<LogRecord> warnings = new ArrayList<>();
        service = new BridgeService(
                SECRET,
                new InlinePlatform(),
                Stubs.logTo(record -> {
                    if (record.getLevel() == Level.WARNING) warnings.add(record);
                }),
                false);
        byte[] garbage = new byte[BridgeProtocol.HMAC_BYTES];
        for (int i = 0; i < 3; i++) {
            service.receive(endpoint, hello(nonce(i + 1)));
            service.receive(endpoint, new Auth(garbage));
        }
        assertEquals(1, warnings.size());

        FakeEndpoint other = new FakeEndpoint(PLAYER);
        service.receive(other, hello(nonce(9)));
        service.receive(other, new Auth(garbage));
        assertEquals(1, warnings.size());
    }

    @Test
    void duplicateHelloResendsTheSameChallenge() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, hello(nonce(1)));
        Challenge first = assertInstanceOf(Challenge.class, endpoint.sent.get(0));
        Challenge second = assertInstanceOf(Challenge.class, endpoint.sent.get(1));
        assertArrayEquals(first.backendNonce(), second.backendNonce());

        service.receive(endpoint, new Auth(hmac(nonce(1), second)));
        assertInstanceOf(Ready.class, endpoint.sent.get(2));
        assertEquals(3, endpoint.sent.size());
        assertEquals(1, handler.ready);
        assertNotNull(service.session(PLAYER));
    }

    @Test
    void helloWithANewNonceRestartsTheHandshake() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, hello(nonce(2)));
        Challenge first = (Challenge) endpoint.sent.get(0);
        Challenge second = (Challenge) endpoint.sent.get(1);
        assertFalse(Arrays.equals(first.backendNonce(), second.backendNonce()));

        service.receive(endpoint, new Auth(hmac(nonce(1), first)));
        assertInstanceOf(Reject.class, endpoint.sent.get(2));
        assertEquals(0, handler.ready);
    }

    @Test
    void handlersOpenTheSessionBeforeReadyIsSent() {
        List<Integer> sentAtReady = new ArrayList<>();
        service.addHandler(new CompanionMessageHandler() {
            @Override
            public void onReady(CompanionSession session) {
                sentAtReady.add(endpoint.sent.size());
                service.setListenerBits(0b11);
            }
        });
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));

        assertEquals(List.of(1), sentAtReady);
        assertEquals(2, endpoint.sent.size());
        Ready ready = assertInstanceOf(Ready.class, endpoint.sent.get(1));
        assertEquals(0b11, ready.listeners());
        assertTrue(service.session(PLAYER).isReady());
    }

    @Test
    void sessionClosedDuringOnReadyNeverSendsReady() {
        service.addHandler(new CompanionMessageHandler() {
            @Override
            public void onReady(CompanionSession session) {
                service.endpointClosed(endpoint);
            }
        });
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));

        assertEquals(1, endpoint.sent.size());
        assertEquals(1, handler.ready);
        assertEquals(1, handler.closed);
        assertNull(service.session(PLAYER));
        assertNull(service.anySession());
    }

    @Test
    void addedCapabilitiesJoinReady() {
        service.addCapabilities(ServerCapability.GRIM_API.flag());
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));
        Ready ready = (Ready) endpoint.sent.get(1);
        assertEquals(BridgeService.SERVED | ServerCapability.GRIM_API.flag(), ready.capabilities());
    }

    @Test
    void versionMismatchIsRejected() {
        service.receive(endpoint, new Hello(BridgeProtocol.VERSION + 1, PLAYER, nonce(1)));
        assertInstanceOf(Reject.class, endpoint.sent.get(0));
        assertNull(endpoint.session);
    }

    @Test
    void olderProxyIsRejectedAndWarnedOnce() {
        List<LogRecord> warnings = new ArrayList<>();
        service = new BridgeService(
                SECRET,
                new InlinePlatform(),
                Stubs.logTo(record -> {
                    if (record.getLevel() == Level.WARNING) warnings.add(record);
                }),
                false);
        service.receive(endpoint, new Hello(BridgeProtocol.VERSION - 1, PLAYER, nonce(1)));
        service.receive(new FakeEndpoint(PLAYER), new Hello(BridgeProtocol.VERSION - 1, PLAYER, nonce(2)));

        assertInstanceOf(Reject.class, endpoint.sent.get(0));
        assertNull(endpoint.session);
        assertEquals(1, warnings.size());
    }

    @Test
    void helloForAnotherPlayerIsRejected() {
        service.receive(endpoint, new Hello(BridgeProtocol.VERSION, UUID.randomUUID(), nonce(1)));
        assertInstanceOf(Reject.class, endpoint.sent.get(0));
        assertNull(endpoint.session);
    }

    @Test
    void latestHelloBeforeJoinIsHandledWhenThePlayerAttaches() {
        FakeEndpoint joining = new FakeEndpoint(null);
        service.receive(joining, hello(nonce(1)));
        service.receive(joining, hello(nonce(2)));
        assertTrue(joining.sent.isEmpty());
        assertNull(joining.session);

        joining.player = PLAYER;
        service.attached(joining);
        service.attached(joining);
        Challenge challenge = assertInstanceOf(Challenge.class, joining.sent.getFirst());
        assertEquals(1, joining.sent.size());

        service.receive(joining, new Auth(hmac(nonce(2), challenge)));
        assertInstanceOf(Ready.class, joining.sent.get(1));
        assertTrue(service.session(PLAYER).isReady());
    }

    @Test
    void attachWithoutAHeldHelloSendsNothing() {
        service.attached(endpoint);
        assertTrue(endpoint.sent.isEmpty());
    }

    @Test
    void heldHelloIsDroppedOnDisconnect() {
        FakeEndpoint joining = new FakeEndpoint(null);
        service.receive(joining, hello(nonce(1)));
        service.endpointClosed(joining);

        joining.player = PLAYER;
        service.attached(joining);
        assertTrue(joining.sent.isEmpty());
    }

    @Test
    void secondHelloRestartsTheHandshake() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));
        CompanionSession first = service.session(PLAYER);

        service.receive(endpoint, hello(nonce(2)));
        Challenge second = assertInstanceOf(Challenge.class, endpoint.sent.get(2));
        assertFalse(first.isReady());
        assertNull(service.session(PLAYER));
        assertEquals(1, handler.closed);

        service.receive(endpoint, new Auth(hmac(nonce(2), second)));
        assertInstanceOf(Ready.class, endpoint.sent.get(3));
        assertTrue(service.session(PLAYER).isReady());
        assertNotNull(service.anySession());
    }

    @Test
    void listenerChangesReachReadySessionsOnce() {
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));

        service.setListenerBits(0b10);
        service.setListenerBits(0b10);
        assertEquals(3, endpoint.sent.size());
        assertEquals(new Listeners(0b10), endpoint.sent.get(2));
    }

    @Test
    void factsFollowReadyOncePerSessionAndPlayerFactsOnlyOnChange() {
        InlinePlatform platform = new InlinePlatform();
        platform.cloudFacts = new CloudFacts(50_000, Map.of("keepalive-interval-ms", "1000"));
        platform.maxNoDamageTicks = 20;
        service = new BridgeService(SECRET, platform, Logger.getLogger("bridge-test"), false);
        service.receive(endpoint, hello(nonce(1)));
        service.receive(endpoint, new Auth(hmac(nonce(1), (Challenge) endpoint.sent.get(0))));

        assertInstanceOf(Ready.class, endpoint.sent.get(1));
        assertEquals(platform.cloudFacts, endpoint.sent.get(2));
        PlayerFacts first = assertInstanceOf(PlayerFacts.class, endpoint.sent.get(3));
        assertEquals("20", first.settings().get("max-no-damage-ticks"));
        assertEquals(4, endpoint.sent.size());
        assertTrue(ServerCapability.has(((Ready) endpoint.sent.get(1)).capabilities(), ServerCapability.CLOUD_FACTS));

        BridgeSession session = (BridgeSession) service.session(PLAYER);
        service.sendPlayerFacts(session);
        assertEquals(4, endpoint.sent.size());

        platform.attackSpeedBase = 1024.0;
        service.sendPlayerFacts(session);
        service.sendPlayerFacts(session);
        assertEquals(5, endpoint.sent.size());
        assertEquals(
                "1024.0",
                assertInstanceOf(PlayerFacts.class, endpoint.sent.get(4))
                        .settings()
                        .get("attack-speed-base"));
    }

    private static Hello hello(byte[] proxyNonce) {
        return new Hello(BridgeProtocol.VERSION, PLAYER, proxyNonce);
    }

    private static byte[] hmac(byte[] proxyNonce, Challenge challenge) {
        return BridgeAuth.hmac(SECRET, PLAYER, proxyNonce, challenge.backendNonce());
    }

    private static byte[] nonce(int seed) {
        byte[] nonce = new byte[BridgeProtocol.NONCE_BYTES];
        nonce[0] = (byte) seed;
        return nonce;
    }

    private static final class FakeEndpoint extends BridgeEndpoint {
        private @Nullable UUID player;
        private final List<BridgeMessage> sent = new ArrayList<>();
        private final List<Boolean> readyWhenSent = new ArrayList<>();

        FakeEndpoint(@Nullable UUID player) {
            this.player = player;
        }

        @Override
        @Nullable
        UUID playerId() {
            return player;
        }

        @Override
        void send(BridgeMessage message) {
            sent.add(message);
            BridgeSession current = session;
            readyWhenSent.add(current != null && current.isReady());
        }

        @Override
        void execute(Runnable task) {
            task.run();
        }
    }

    private static final class InlinePlatform implements BridgePlatform {
        private @Nullable CloudFacts cloudFacts;
        private int maxNoDamageTicks = -1;
        private double attackSpeedBase = 4.0;

        @Override
        public void runMain(Runnable task) {
            task.run();
        }

        @Override
        public @Nullable Player player(UUID player) {
            return null;
        }

        @Override
        public Ready ready(UUID player, byte[] hmac, long capabilities, int listeners) {
            return new Ready(hmac, capabilities, "test", WORLD, "world", listeners);
        }

        @Override
        public @Nullable CloudFacts cloudFacts() {
            return cloudFacts;
        }

        @Override
        public @Nullable PlayerFacts playerFactsChanged(BridgeSession session) {
            if (maxNoDamageTicks < 0 || !session.playerFactsChanged(maxNoDamageTicks, attackSpeedBase)) return null;
            return new PlayerFacts(Map.of(
                    "max-no-damage-ticks",
                    String.valueOf(maxNoDamageTicks),
                    "attack-speed-base",
                    String.valueOf(attackSpeedBase)));
        }
    }

    private static final class RecordingHandler implements CompanionMessageHandler {
        private final List<BridgeMessage> messages = new ArrayList<>();
        private int ready;
        private int closed;

        @Override
        public void onReady(CompanionSession session) {
            ready++;
        }

        @Override
        public void onMessage(CompanionSession session, BridgeMessage message) {
            messages.add(message);
        }

        @Override
        public void onClosed(CompanionSession session) {
            closed++;
        }
    }
}
