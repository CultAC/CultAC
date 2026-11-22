package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Sits where {@code cult-decoder} sits on a connection CultAC does not inspect. It reads the packet id of each
 * serverbound frame and nothing else, except a pong's id while pings written for a removed player may still be answered:
 * those pongs are swallowed as the decoder would. It hands each reconfiguration acknowledgement to its {@link Hooks}
 * before forwarding it. All of it runs on the channel's event loop.
 */
public final class DormantWatch extends ChannelInboundHandlerAdapter {
    public static final String NAME = "cult-dormant";
    /** State key: whether the connection completed a CultAC login before it went dormant. */
    public static final String LOGIN_NOTIFIED = "login-notified";
    /** State key: marks a released connection as dormant. */
    public static final String DORMANT = "dormant";
    /** State key: the ids of CultAC pings the client may still answer, as an {@code int[]}. */
    public static final String RETIRED = "retired";

    private static final Set<DormantWatch> WATCHES = ConcurrentHashMap.newKeySet();
    private static final Map<ProtocolVersion, Ids> IDS = new ConcurrentHashMap<>();

    /** What the platform does for a watched connection. */
    public interface Hooks {
        /**
         * The client acknowledged a reconfiguration; the acknowledgement is forwarded after this returns. On the
         * channel's event loop.
         *
         * @return whether the watch was removed and the connection handed to a generation or attached again
         * @throws Exception when the move failed partway
         */
        boolean acknowledged(DormantWatch watch) throws Exception;

        /** The connection closed while watched. */
        void closed(DormantWatch watch);
    }

    /** The serverbound packet ids the watch reacts to; -1 for one the protocol lacks. */
    record Ids(int playPong, int acknowledged, int configurationPong, int finish) {
        static Ids of(ProtocolVersion version) {
            ProtocolData data = ProtocolData.load(version);
            IdTable play = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
            IdTable configuration = data.packets(ConnectionPhase.CONFIGURATION, PacketDirection.SERVERBOUND);
            return new Ids(
                    play.id("minecraft:pong"),
                    play.id("minecraft:configuration_acknowledged"),
                    configuration.id("minecraft:pong"),
                    configuration.id("minecraft:finish_configuration"));
        }
    }

    private final Channel channel;
    private final UUID player;
    private final @Nullable Object nativePlayer;
    private final @Nullable Object platformPlayer;
    private final Map<String, Object> state;
    private final Hooks hooks;
    private final Ids ids;
    private final IntOpenHashSet retired;
    private boolean play;

    /**
     * @param state the connection's continuation and {@link #LOGIN_NOTIFIED}, as JDK values
     * @param retired ids of CultAC pings the client may still answer
     * @param play whether the client's serverbound stream is in play, else in configuration
     */
    public DormantWatch(
            Channel channel,
            ProtocolVersion version,
            UUID player,
            @Nullable Object nativePlayer,
            @Nullable Object platformPlayer,
            Map<String, Object> state,
            int[] retired,
            boolean play,
            Hooks hooks) {
        this.channel = channel;
        this.player = player;
        this.nativePlayer = nativePlayer;
        this.platformPlayer = platformPlayer;
        this.state = Map.copyOf(state);
        this.hooks = hooks;
        this.ids = IDS.computeIfAbsent(version, Ids::of);
        this.retired = new IntOpenHashSet(retired);
        this.play = play;
    }

    /** The watches installed on this generation's connections. */
    public static Collection<DormantWatch> all() {
        return Collections.unmodifiableSet(WATCHES);
    }

    public Channel channel() {
        return channel;
    }

    public UUID player() {
        return player;
    }

    public @Nullable Object nativePlayer() {
        return nativePlayer;
    }

    /** The platform player the connection had while inspected; null for one that joined dormant or was handed over. */
    public @Nullable Object platformPlayer() {
        return platformPlayer;
    }

    /** The connection's continuation and {@link #LOGIN_NOTIFIED}. */
    public Map<String, Object> state() {
        return state;
    }

    public boolean loginNotified() {
        return Boolean.TRUE.equals(state.get(LOGIN_NOTIFIED));
    }

    /** The ids of CultAC pings the client may still answer. On the channel's event loop. */
    public int[] retired() {
        return retired.toIntArray();
    }

    /** Adds {@code ids} to the CultAC pings the client may still answer. On the channel's event loop. */
    void retire(int[] ids) {
        for (int id : ids) retired.add(id);
    }

    /** Whether the client's serverbound stream is in play. On the channel's event loop. */
    public boolean play() {
        return play;
    }

    /**
     * Removes this watch for a newer generation: what that generation adopts, {@link #state()} with {@link #DORMANT}
     * and {@link #RETIRED}. On the channel's event loop.
     */
    public Map<String, Object> release() {
        Map<String, Object> released = new HashMap<>(state);
        released.put(DORMANT, true);
        released.put(RETIRED, retired());
        if (channel.pipeline().context(this) != null) channel.pipeline().remove(this);
        return released;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        WATCHES.add(this);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        WATCHES.remove(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            hooks.closed(this);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("dormant connection close", failure);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame) || !frame.isReadable()) {
            ctx.fireChannelRead(message);
            return;
        }
        int at = frame.readerIndex();
        int end = frame.writerIndex();
        int id = 0;
        for (int shift = 0; ; shift += 7) {
            if (at >= end || shift > 28) {
                id = -1;
                break;
            }
            byte next = frame.getByte(at++);
            id |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) break;
        }
        if (id >= 0) {
            if (play) {
                if (id == ids.playPong) {
                    if (swallowed(frame, at)) return;
                } else if (id == ids.acknowledged) {
                    acknowledged(ctx, frame);
                    return;
                }
            } else if (id == ids.configurationPong) {
                if (swallowed(frame, at)) return;
            } else if (id == ids.finish) play = true;
        }
        ctx.fireChannelRead(frame);
    }

    /** Takes a pong answering a retired ping and releases it unless {@code disable-pong-cancelling} forwards it. */
    private boolean swallowed(ByteBuf frame, int at) {
        if (retired.isEmpty() || frame.writerIndex() - at < Integer.BYTES || !retired.remove(frame.getInt(at)))
            return false;
        if (CultAPI.INSTANCE.getConfigManager().isDisablePongCancelling()) return false;
        frame.release();
        return true;
    }

    private void acknowledged(ChannelHandlerContext ctx, ByteBuf frame) {
        // The client answers every ping written before the reconfiguration ahead of this acknowledgement.
        retired.clear();
        boolean left;
        try {
            left = hooks.acknowledged(this);
        } catch (Throwable failure) {
            if (CultAPI.INSTANCE.getConfigManager().failsOpen()) {
                LogUtil.warn("Not inspecting " + player + "; resuming at a reconfiguration failed", failure);
                if (ctx.pipeline().context(this) != null) ctx.pipeline().remove(this);
                ctx.fireChannelRead(frame);
                return;
            }
            CultNetworkManager.reportFailure("dormant reconfiguration", failure);
            frame.release();
            ctx.channel().close();
            return;
        }
        if (!ctx.channel().isActive()) {
            frame.release();
            return;
        }
        if (!left) play = false;
        ctx.fireChannelRead(frame);
    }
}
