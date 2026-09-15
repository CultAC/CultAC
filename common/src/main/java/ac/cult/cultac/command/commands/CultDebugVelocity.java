package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.floodgate.GeyserUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.session.GeyserSession;

public final class CultDebugVelocity implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(manager.commandBuilder("cult", "cultac", "grim", "grimac")
                .literal("debug")
                .literal("velocity", Description.of("Send yourself a velocity after a delay in ticks"))
                .permission("cult.debug")
                .required("ticks", IntegerParser.integerParser(0))
                .required("x", DoubleParser.doubleParser())
                .required("y", DoubleParser.doubleParser())
                .required("z", DoubleParser.doubleParser())
                .flag(manager.flagBuilder("silent")
                        .withDescription(Description.of("Hide this velocity from Grim; implies --geyser for Bedrock")))
                .flag(manager.flagBuilder("geyser")
                        .withDescription(Description.of("Send native motion through Geyser (Bedrock players only)")))
                .handler(this::handle));
    }

    private void handle(CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlatformPlayer platformPlayer = sender.getPlatformPlayer();
        if (platformPlayer == null) {
            sender.sendMessage(Component.text("Run this command as a player.", NamedTextColor.RED));
            return;
        }
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (player == null) {
            sender.sendMessage(Component.text("You must be online and checked by Grim.", NamedTextColor.RED));
            return;
        }
        boolean silent = context.flags().isPresent("silent");
        boolean geyser = context.flags().isPresent("geyser");
        if (geyser && !player.isBedrockMovement()) {
            sender.sendMessage(Component.text("--geyser is only available to Bedrock players.", NamedTextColor.RED));
            return;
        }
        boolean useGeyser = geyser || (silent && player.isBedrockMovement());
        Vec3 velocity = new Vec3(context.get("x"), context.get("y"), context.get("z"));
        if (!Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)
                || (player.isBedrockMovement() && (!Float.isFinite((float) velocity.x)
                || !Float.isFinite((float) velocity.y) || !Float.isFinite((float) velocity.z)))) {
            sender.sendMessage(Component.text("Velocity must contain finite numbers representable by your client.", NamedTextColor.RED));
            return;
        }
        int ticks = context.get("ticks");
        Runnable send = () -> sendVelocity(player, sender, velocity, silent, useGeyser);
        if (ticks == 0) {
            send.run();
        } else if (CultAPI.INSTANCE.getScheduler().getEntityScheduler().runDelayed(
                platformPlayer, CultAPI.INSTANCE.getGrimPlugin(), send, null, ticks) != null) {
            sender.sendMessage(Component.text("Scheduled " + (silent ? "silent " : "")
                    + "velocity " + velocity + " in " + ticks + " ticks via "
                    + (useGeyser ? "Geyser" : "a Java packet") + ".", NamedTextColor.GRAY));
        }
    }

    private static void sendVelocity(CultPlayer player, Sender sender, Vec3 velocity, boolean silent, boolean useGeyser) {
        player.runSafely(() -> {
            // Never deliver an old command to a new connection with the same UUID.
            if (CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId()) != player) return;
            if (useGeyser) {
                if (!GeyserUtil.isGeyserAvailable()) {
                    sender.sendMessage(Component.text("Local Geyser is unavailable.", NamedTextColor.RED));
                    return;
                }
                BedrockVelocity.send(player, velocity, silent,
                        message -> sender.sendMessage(Component.text(message, NamedTextColor.GRAY)));
                return;
            }
            // ClientPacketListener#handleSetEntityMotion -> Entity#lerpMotion replaces
            // client velocity. Sending only the packet avoids a second Bukkit velocity broadcast.
            var packet = new ClientboundSetEntityMotionPacket(player.entityID, velocity);
            if (silent) player.user.sendPacketSilently(packet);
            else player.user.sendPacket(packet);
            sender.sendMessage(Component.text("Sent " + (silent ? "silent " : "") + "velocity " + velocity + " via a Java packet.",
                    NamedTextColor.GRAY));
        });
    }

    // Keep optional Geyser types and all debug-only transport access in this command.
    static final class BedrockVelocity {
        static void send(CultPlayer player, Vec3 velocity, boolean silent, Consumer<String> feedback) {
            if (!(GeyserApi.api().connectionByUuid(player.playerUUID) instanceof GeyserSession session)) {
                feedback.accept("Player has no active local Geyser session.");
                return;
            }
            session.executeInEventLoop(() -> {
                if (session.isClosed()
                        || CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player.playerUUID) != player
                        || GeyserApi.api().connectionByUuid(player.playerUUID) != session) return;
                // Match JavaSetEntityMotionTranslator's runtime ID and float vector.
                var packet = new SetEntityMotionPacket();
                packet.setRuntimeEntityId(session.getPlayerEntity().geyserId());
                packet.setMotion(Vector3f.from(velocity.x, velocity.y, velocity.z));
                if (!silent) {
                    session.sendUpstreamPacket(packet);
                    feedback.accept("Sent velocity " + packet.getMotion() + " via Geyser.");
                    return;
                }
                var upstream = session.getUpstream().getSession();
                ChannelHandlerContext observer = observerContext(upstream.getPeer().getChannel().pipeline());
                if (observer == null || upstream.isSubClient()) {
                    feedback.accept("Geyser's primary connection and Grim outbound observer are required for silent velocity.");
                    return;
                }
                // Geyser uses the primary Bedrock session (both sub-client IDs are 0).
                // Context.write starts BEFORE this outbound handler, preserving the
                // logger and Cloudburst encoding without invoking Grim's motion observer.
                observer.executor().execute(() -> {
                    if (observer.isRemoved() || !observer.channel().isActive() || session.isClosed()
                            || CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player.playerUUID) != player
                            || GeyserApi.api().connectionByUuid(player.playerUUID) != session) return;
                    writeSilently(observer, packet)
                            .addListener(result -> feedback.accept(result.isSuccess()
                                    ? "Sent silent velocity " + packet.getMotion() + " via Geyser."
                                    : "Could not send silent velocity: " + result.cause().getClass().getSimpleName()));
                });
            });
        }

        static ChannelFuture writeSilently(ChannelHandlerContext observer, SetEntityMotionPacket packet) {
            return observer.writeAndFlush(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        }

        static ChannelHandlerContext observerContext(ChannelPipeline pipeline) {
            for (var entry : pipeline) {
                if (entry.getValue() instanceof ChannelOutboundHandler
                        && entry.getValue().getClass().getEnclosingClass() == GeyserBedrockBridgeRuntime.class) {
                    return pipeline.context(entry.getValue());
                }
            }
            return null;
        }
    }
}
