package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.IntegerParser;

public final class CultDebugExplosion implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        manager.command(BuildableCommand.root(manager)
                .literal("debug")
                .literal("explosion", Description.of("Send yourself an explosion knockback after a delay in ticks"))
                .permission("cult.debug")
                .required("ticks", IntegerParser.integerParser(0))
                .required("x", DoubleParser.doubleParser())
                .required("y", DoubleParser.doubleParser())
                .required("z", DoubleParser.doubleParser())
                .flag(manager.flagBuilder("silent")
                        .withDescription(Description.of("Hide this explosion from Grim (Java players only)")))
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
        if (silent && player.isBedrockMovement()) {
            // Geyser translates the explosion into native motion that Grim's Bedrock bridge observes.
            sender.sendMessage(Component.text(
                    "--silent is only available to Java players; use "
                            + CloudCommandService.command("debug velocity --silent") + ".",
                    NamedTextColor.RED));
            return;
        }
        Vec3 knockback = new Vec3(context.get("x"), context.get("y"), context.get("z"));
        if (!Double.isFinite(knockback.x)
                || !Double.isFinite(knockback.y)
                || !Double.isFinite(knockback.z)
                || (player.isBedrockMovement()
                        && (!Float.isFinite((float) knockback.x)
                                || !Float.isFinite((float) knockback.y)
                                || !Float.isFinite((float) knockback.z)))) {
            sender.sendMessage(Component.text(
                    "Knockback must contain finite numbers representable by your client.", NamedTextColor.RED));
            return;
        }
        int ticks = context.get("ticks");
        Runnable send = () -> sendExplosion(player, sender, knockback, silent);
        if (ticks == 0) {
            send.run();
        } else if (CultAPI.INSTANCE
                        .getScheduler()
                        .getEntityScheduler()
                        .runDelayed(platformPlayer, CultAPI.INSTANCE.getGrimPlugin(), send, null, ticks)
                != null) {
            sender.sendMessage(Component.text(
                    "Scheduled " + (silent ? "silent " : "") + "explosion " + knockback + " in " + ticks + " ticks.",
                    NamedTextColor.GRAY));
        }
    }

    private static void sendExplosion(CultPlayer player, Sender sender, Vec3 knockback, boolean silent) {
        player.runSafely(() -> {
            // Never deliver an old command to a new connection with the same UUID.
            if (CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId()) != player) return;
            if (!player.canSendPlayPackets()) {
                sender.sendMessage(Component.text("Player is not in play", NamedTextColor.RED));
                return;
            }
            // ClientPacketListener#handleExplosion -> LocalPlayer#addDeltaMovement adds to the
            // client's velocity, even while riding. The center only places the sound and particle.
            var packet = new ClientboundExplode(
                    new Vec3d(player.x, player.y, player.z), new Vec3d(knockback.x, knockback.y, knockback.z));
            player.user.write(new CultWrite(packet, silent));
            sender.sendMessage(Component.text(
                    "Sent " + (silent ? "silent " : "") + "explosion " + knockback + " via a Java packet.",
                    NamedTextColor.GRAY));
        });
    }
}
