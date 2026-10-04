package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import io.netty.channel.Channel;

/** Offline tests explicitly create a PLAY session even when no transport handlers are installed. */
public final class TestUsers {
    private TestUsers() {}

    public static User create(User.Profile profile, Channel channel) {
        return create(profile, channel, null);
    }

    public static User create(User.Profile profile, Channel channel, PlatformConnection platform) {
        var session = new CultConnection(
                platform, channel, CultAPI.INSTANCE.getNetworkManager().dispatcher(), ignored -> null);
        for (var direction : PacketDirection.values()) session.phase(direction, ConnectionPhase.PLAY);
        return new User(profile, session);
    }

    static void attach(User user, CultPlayer player) {
        synchronized (user.getCultConnection()) {
            user.getCultConnection().player(player);
        }
    }
}
