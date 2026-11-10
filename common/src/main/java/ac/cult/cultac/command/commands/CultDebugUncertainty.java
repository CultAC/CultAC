package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.DoubleParser;

/**
 * Temporary, connection-local prediction allowance for manual debugging. A player sets their own; the console (or a
 * player) names a target after the chance, so a live harness can drive it from the proxy console.
 */
public final class CultDebugUncertainty implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> manager, CloudPlatformCommandArguments arguments) {
        var command = BuildableCommand.root(manager)
                .literal("debug")
                .literal("uncertainty", Description.of("Set a temporary prediction uncertainty in blocks per axis"))
                .permission("cult.debug");
        var set = command.required("x", DoubleParser.doubleParser(0))
                .required("y", DoubleParser.doubleParser(0))
                .required("z", DoubleParser.doubleParser(0))
                .optional("chancePercent", DoubleParser.doubleParser(0, 100));
        var reset = command.literal("reset", Description.of("Clear a temporary prediction uncertainty"));
        if (arguments != null) {
            set = set.optional("target", arguments.singlePlayerSelectorParser());
            reset = reset.optional("target", arguments.singlePlayerSelectorParser());
        }
        manager.command(set.handler(context -> set(
                context.sender(),
                target(context.<PlayerSelector>getOrDefault("target", null)),
                new Vec3(context.get("x"), context.get("y"), context.get("z")),
                context.getOrDefault("chancePercent", 100.0D))));
        manager.command(reset.handler(context -> set(
                context.sender(), target(context.<PlayerSelector>getOrDefault("target", null)), Vec3.ZERO, 100.0D)));
    }

    private static Sender target(PlayerSelector selector) {
        return selector == null ? null : selector.getSinglePlayer();
    }

    private static void set(Sender sender, Sender named, Vec3 radius, double chancePercent) {
        if (!Double.isFinite(radius.x) || !Double.isFinite(radius.y) || !Double.isFinite(radius.z)) {
            sender.sendMessage(
                    Component.text("Uncertainty must contain finite, non-negative numbers.", NamedTextColor.RED));
            return;
        }
        if (!Double.isFinite(chancePercent)) {
            sender.sendMessage(Component.text("Chance must be a finite percentage from 0 to 100.", NamedTextColor.RED));
            return;
        }
        if (named == null && sender.getPlatformPlayer() == null) {
            sender.sendMessage(
                    Component.text("Name a target player when running this from the console.", NamedTextColor.RED));
            return;
        }
        Sender subject = named == null ? sender : named;
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(subject.getUniqueId());
        if (player == null) {
            sender.sendMessage(Component.text(
                    named == null ? "You must be online and checked by Grim." : "That player is not checked by Grim.",
                    NamedTextColor.RED));
            return;
        }
        String who = named == null ? "your" : player.getName() + "'s";
        player.runSafely(() -> {
            if (CultAPI.INSTANCE.getPlayerDataManager().getPlayer(subject.getUniqueId()) != player) return;
            player.debugUncertainty = radius;
            player.debugUncertaintyChancePercent = chancePercent;
            boolean reset = radius.x == 0 && radius.y == 0 && radius.z == 0;
            sender.sendMessage(Component.text(
                    reset
                            ? "Reset " + who + " temporary prediction uncertainty."
                            : "Set " + who + " temporary prediction uncertainty to +/- " + radius.x + ", " + radius.y
                                    + ", " + radius.z + " blocks (X, Y, Z), with a " + chancePercent
                                    + "% chance each tick. Lasts until reconnect or "
                                    + CloudCommandService.command("debug uncertainty reset") + ".",
                    NamedTextColor.GRAY));
        });
    }
}
