package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.NetworkService;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientBrandReportTest {
    private static final UUID PLAYER = UUID.fromString("0f6f7b54-6f5e-4c43-9a4a-0b2c9f9b2d12");
    private static final String CHANNEL = "minecraft:brand";

    private final TestNetworks networks = new TestNetworks();
    private NetworkService here;
    private NetworkService peer;
    private ClientBrand check;

    @BeforeAll
    static void config() {
        OfflineCultTestBootstrap.installConfig();
    }

    @AfterEach
    void tearDown() {
        NetworkModule.stop();
    }

    private void connected() {
        here = networks.service("p3")
                .platform(new Players().with(PLAYER, "Branded", "lobby"))
                .start();
        TestNetworks.install(here);
        peer = networks.started("p7");
        User user = TestUsers.create(new User.Profile(PLAYER, "Branded"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        TestUsers.attach(user, player);
        check = player.checkManager.getCheck(ClientBrand.class);
    }

    private static byte[] payload(String brand) {
        byte[] text = brand.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = text.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    private String peerBrand() {
        return peer.locate(PLAYER.toString()).orElseThrow().brand();
    }

    private int rosters() {
        return networks.broadcasts("roster").size();
    }

    @Test
    void eachDifferentBrandReachesThePeersInTheNextDeltaAndTheSameOneSendsNothing() {
        connected();
        // A connection another engine already announced, so the check flags and alerts nothing more here.
        check.restore("vanilla");
        check.handle(CHANNEL, payload("vanilla"));
        TestNetworks.sendDelta(here);
        assertEquals("vanilla", peerBrand());

        check.handle(CHANNEL, payload("fabric"));
        TestNetworks.sendDelta(here);
        assertEquals("fabric", peerBrand());
        assertEquals("vanilla", check.getBrand());

        int sent = rosters();
        check.handle(CHANNEL, payload("fabric"));
        TestNetworks.sendDelta(here);
        assertEquals(sent, rosters());
    }

    @Test
    void aDifferentObservedBrandReachesThePeersInTheNextDeltaAndTheSameOneSendsNothing() {
        connected();
        check.restore("vanilla");
        check.observe("vanilla");
        TestNetworks.sendDelta(here);
        assertEquals("vanilla", peerBrand());

        check.observe("fabric");
        TestNetworks.sendDelta(here);
        assertEquals("fabric", peerBrand());
        assertEquals("vanilla", check.getBrand());

        int sent = rosters();
        check.observe("fabric");
        TestNetworks.sendDelta(here);
        assertEquals(sent, rosters());
    }

    @Test
    void anOversizedFirstBrandIsReportedCut() {
        connected();
        check.handle(CHANNEL, payload("y".repeat(100)));
        assertEquals("sent 101 bytes as brand", check.getBrand());
        TestNetworks.sendDelta(here);
        assertEquals("y".repeat(64), peerBrand());
    }
}
