package ac.cult.cultac.network.netty.channel;

import io.netty.channel.Channel;

public final class ChannelHelper {
    private ChannelHelper() {}

    public static boolean isOpen(Object channel) {
        return channel instanceof Channel nettyChannel && nettyChannel.isOpen();
    }
}
