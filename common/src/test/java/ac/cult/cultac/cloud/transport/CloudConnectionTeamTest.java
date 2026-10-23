package ac.cult.cultac.cloud.transport;

import static ac.cult.cultac.cloud.transport.ConnectionFixture.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.PortalRole;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.intake.v3.TeamState;
import ac.cult.cultac.cloud.intake.v3.TeamSweep;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Globals;
import ac.cult.cultac.cloud.transport.ConnectionFixture.Receiver;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CloudConnectionTeamTest {
    private static final UUID PLAYER = UUID.fromString("7445efd5-b580-47d0-b669-bf621fe82396");

    private static final class Heard implements TeamSocket.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        volatile TeamSocket socket;

        @Override
        public void opened(TeamSocket socket, @Nullable Boolean enabled) {
            this.socket = socket;
            events.add("opened " + enabled);
        }

        @Override
        public void state(TeamSocket socket, boolean enabled) {
            events.add("state " + enabled);
        }

        @Override
        public void sweep(TeamSocket socket, List<UUID> players) {
            events.add("sweep " + players);
        }

        @Override
        public void closed(TeamSocket socket) {
            events.add("closed");
        }

        String next() throws InterruptedException {
            return events.poll(5, TimeUnit.SECONDS);
        }
    }

    private static CloudSettings settings(String url, boolean team) {
        return new CloudSettings(
                url, "test-key", CloudSettings.DEFAULT_SERVER_ID, "proxy", CloudSettings.PROTOCOLS, false, team);
    }

    private static void reply(Channel channel, Reply.Builder reply) {
        byte[] bytes = Replies.newBuilder().addReplies(reply).build().toByteArray();
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
    }

    private static JsonObject connectBody(Receiver receiver) throws InterruptedException {
        String body = receiver.connectBodies.poll(5, TimeUnit.SECONDS);
        assertNotNull(body);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    @Test
    void anAnsweredSocketCarriesTeamTrafficBothWays() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            Heard heard = new Heard();
            receiver.connections.onTeam(heard);
            receiver.connections.configure(settings(receiver.url(), true));
            receiver.connections.forServer("proxy.a");
            receiver.allowConnect("\"protocol\":5,\"team\":{\"enabled\":true}");

            assertEquals(TeamSocket.VERSION, connectBody(receiver).get("team").getAsInt());
            assertEquals("opened true", heard.next());
            Channel channel = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(channel);

            byte[] roles = IntakeWire.team(
                    List.of(IntakeWire.teamRole(PLAYER, "Disregard1", PortalRole.PORTAL_ROLE_MODERATOR, false)));
            await(() -> heard.socket.send(roles), "the socket takes the TeamRoles");
            IntakeMessage sent = receiver.take(IntakeMessage.KindCase.TEAM);
            assertEquals("Disregard1", sent.getTeam().getPlayers(0).getName());

            reply(channel, Reply.newBuilder().setTeamState(TeamState.getDefaultInstance()));
            assertEquals("state false", heard.next());
            reply(
                    channel,
                    Reply.newBuilder()
                            .setTeamSweep(TeamSweep.newBuilder()
                                    .addPlayers(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))));
            assertEquals("sweep [" + PLAYER + "]", heard.next());

            channel.close();
            assertEquals("closed", heard.next());
        }
    }

    @Test
    void aTeamAnswerWithoutEnabledOpensDisabled() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            Heard heard = new Heard();
            receiver.connections.onTeam(heard);
            receiver.connections.configure(settings(receiver.url(), true));
            receiver.connections.forServer("proxy.a");
            receiver.allowConnect("\"protocol\":5,\"team\":{}");

            assertEquals("opened false", heard.next());
            Channel channel = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(channel);
            reply(channel, Reply.newBuilder().setTeamState(TeamState.newBuilder().setEnabled(true)));
            assertEquals("state true", heard.next());
        }
    }

    @Test
    void aSocketWithoutATeamAnswerHearsNoTeamTraffic() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            Heard heard = new Heard();
            receiver.connections.onTeam(heard);
            receiver.connections.configure(settings(receiver.url(), true));
            receiver.connections.forServer("proxy.a");
            receiver.allowConnect();

            assertEquals("opened null", heard.next());
            Channel channel = receiver.handshakes.poll(5, TimeUnit.SECONDS);
            assertNotNull(channel);
            reply(channel, Reply.newBuilder().setTeamState(TeamState.newBuilder().setEnabled(true)));
            reply(
                    channel,
                    Reply.newBuilder()
                            .setTeamSweep(TeamSweep.newBuilder()
                                    .addPlayers(ByteString.copyFrom(IntakeWire.uuid(PLAYER)))));
            channel.close();

            assertNull(heard.events.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void connectOffersTeamOnlyWhenTheSettingsTurnItOn() throws Exception {
        try (Globals globals = new Globals();
                Receiver receiver = new Receiver()) {
            Heard heard = new Heard();
            receiver.connections.onTeam(heard);
            receiver.connections.configure(settings(receiver.url(), false));
            receiver.connections.forServer("proxy.a");
            receiver.allowConnect("\"protocol\":5,\"team\":{\"enabled\":true}");

            assertFalse(connectBody(receiver).has("team"));
            assertNotNull(receiver.handshakes.poll(5, TimeUnit.SECONDS));
            assertNull(heard.events.poll(200, TimeUnit.MILLISECONDS));
        }
    }
}
