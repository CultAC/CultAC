package ac.cult.cultac.network.netty.channel;

import io.netty.channel.Channel;
import java.util.stream.Collectors;

public final class ChannelHelper {
    private ChannelHelper() {}

    public static boolean isOpen(Object channel) {
        return channel instanceof Channel nettyChannel && nettyChannel.isOpen();
    }

    public static String pipelineHandlerNamesAsString(Object channel) {
        if (!(channel instanceof Channel nettyChannel)) {
            return "";
        }
        return nettyChannel.pipeline().names().stream().collect(Collectors.joining(", "));
    }
}
