package ac.cult.cultac.network.packet;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsX;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.vehicle.VehicleB;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.player.CultPlayer;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaChannelHandler;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

/**
 * Preserves the identity of legacy input packets before ViaBackwards maps them
 * onto the 1.21.2+ input bitset. Sneaking must follow these original packets:
 * Via's periodically synthesized on-foot input can arrive after movement.
 */
public final class LegacyViaInputBridge extends ChannelInboundHandlerAdapter {
    public static final String HANDLER_NAME = "cult-legacy-via-input";

    private static final String PLAYER_COMMAND = "PLAYER_COMMAND";
    private static final String PLAYER_INPUT = "PLAYER_INPUT";

    private final User user;
    private final UserConnection viaConnection;

    private LegacyViaInputBridge(User user, UserConnection viaConnection) {
        this.user = user;
        this.viaConnection = viaConnection;
    }

    public static void install(User user) {
        if (!ViaVersionUtil.isAvailable()) {
            return;
        }

        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            return;
        }

        Runnable install = () -> installOnEventLoop(user, channel);
        if (channel.eventLoop().inEventLoop()) {
            install.run();
        } else {
            channel.eventLoop().execute(install);
        }
    }

    public static void remove(User user) {
        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            return;
        }

        Runnable remove = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
        } else {
            channel.eventLoop().execute(remove);
        }
    }

    private static void installOnEventLoop(User user, Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER_NAME) != null) {
            return;
        }

        String decoderName = Via.getManager().getInjector().getDecoderName();
        ChannelHandler decoder = pipeline.get(decoderName);
        if (!(decoder instanceof ViaChannelHandler viaHandler)) {
            return;
        }

        ProtocolPipeline protocolPipeline = viaHandler.connection().getProtocolInfo().getPipeline();
        if (protocolPipeline == null || !protocolPipeline.hasNonBaseProtocols()) {
            return;
        }

        pipeline.addBefore(decoderName, HANDLER_NAME, new LegacyViaInputBridge(user, viaHandler.connection()));
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        boolean rejected = false;
        if (message instanceof ByteBuf buffer) {
            try {
                rejected = inspect(buffer);
            } catch (RuntimeException ignored) {
                // Never interfere with Via or vanilla decoding on an unknown frame.
            }
        }

        if (rejected) {
            ReferenceCountUtil.release(message);
            return;
        }
        context.fireChannelRead(message);
    }

    private boolean inspect(ByteBuf original) {
        ProtocolInfo protocolInfo = viaConnection.getProtocolInfo();
        if (protocolInfo == null || protocolInfo.getClientState() != State.PLAY) {
            return false;
        }

        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (player == null || player.isBedrockMovement()
                || protocolInfo.protocolVersion() == null
                || protocolInfo.protocolVersion().getVersion()
                >= ClientVersion.V_1_21_2.getProtocolVersion()) {
            return false;
        }

        ByteBuf packet = original.duplicate();
        int packetId = readVarInt(packet);
        String packetName = originalPacketName(protocolInfo.getPipeline(), packetId);
        if (PLAYER_INPUT.equals(packetName)) {
            // Legacy steer input is exactly two floats followed by one flags byte.
            if (packet.readableBytes() != Float.BYTES * 2 + Byte.BYTES) {
                return false;
            }
            packet.skipBytes(Float.BYTES * 2);
            return handleLegacySteer(player, (packet.readByte() & 2) != 0);
        }

        if (!PLAYER_COMMAND.equals(packetName)) {
            return false;
        }

        readVarInt(packet); // entity id
        int action = readVarInt(packet);
        readVarInt(packet); // action data
        if (packet.isReadable() || action < 0 || action > 1) {
            return false;
        }
        return handleLegacySneak(player, action == 0);
    }

    @SuppressWarnings("rawtypes")
    private static String originalPacketName(ProtocolPipeline pipeline, int packetId) {
        if (pipeline == null) {
            return null;
        }

        List<Protocol> protocols = pipeline.pipes();
        int firstTranslation = pipeline.baseProtocolCount();
        if (firstTranslation >= protocols.size()) {
            return null;
        }

        PacketType type = (PacketType) protocols.get(firstTranslation)
                .getPacketTypesProvider().unmappedServerboundType(State.PLAY, packetId);
        return type == null ? null : type.getName();
    }

    private static int readVarInt(ByteBuf buffer) {
        int result = 0;
        for (int position = 0; position < 32; position += 7) {
            if (!buffer.isReadable()) {
                throw new IllegalArgumentException("Truncated VarInt");
            }
            byte current = buffer.readByte();
            result |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("VarInt is too large");
    }

    private static boolean handleLegacySteer(CultPlayer player, boolean sneaking) {
        boolean rejected = player.checkManager.getCheck(VehicleTimer.class).handleLegacySteerVehicle();
        player.checkManager.getCheck(BadPacketsE.class).handleLegacySteerVehicle();
        player.checkManager.getCheck(BadPacketsR.class).handleLegacySteerVehicle();
        rejected = player.checkManager.getCheck(VehicleB.class).handleLegacySteerVehicle() || rejected;
        if (!rejected) {
            player.isSneaking = sneaking;
        }
        return rejected;
    }

    private static boolean handleLegacySneak(CultPlayer player, boolean sneaking) {
        boolean rejected = player.checkManager.getCheck(BadPacketsG.class).handleLegacySneak(sneaking);
        player.checkManager.getCheck(BadPacketsX.class).handleLegacySneakAction();
        player.packetOrderProcessor.handleLegacySneakAction();
        if (!rejected) {
            player.isSneaking = sneaking;
        }
        return rejected;
    }
}
