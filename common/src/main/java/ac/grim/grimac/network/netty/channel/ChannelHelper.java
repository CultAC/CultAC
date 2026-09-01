package ac.grim.grimac.network.netty.channel;

import io.netty.channel.Channel;

import java.util.stream.Collectors;

public final class ChannelHelper {
    private ChannelHelper() {
    }

    public static void runInEventLoop(Object channel, Runnable runnable) {
        if (!(channel instanceof Channel nettyChannel)) {
            runnable.run();
            return;
        }

        if (nettyChannel.eventLoop().inEventLoop()) {
            runnable.run();
        } else {
            nettyChannel.eventLoop().execute(runnable);
        }
    }

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
