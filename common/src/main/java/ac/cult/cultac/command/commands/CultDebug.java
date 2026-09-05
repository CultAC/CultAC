package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.manager.player.SmoketestControlBridge;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.registries.BuiltInRegistries;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CultDebug implements BuildableCommand {

    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> cultCommand = commandManager.commandBuilder("cult", "cultac", "grim", "grimac");

        // Register "debug" subcommand
        Command.Builder<Sender> debugCommand = cultCommand
                .literal("debug", Description.of("Toggle debug output for a player"))
                .permission("cult.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebug);

        // Register "consoledebug" subcommand
        Command.Builder<Sender> consoleDebugCommand = cultCommand
                .literal("consoledebug", Description.of("Toggle console debug output for a player"))
                .permission("cult.consoledebug")
                .required("target", arguments.singlePlayerSelectorParser())
                .optional("state", StringParser.stringParser())
                .handler(this::handleConsoleDebug);

        // Register "debug poolstats <label>" — SectionPool dedup statistics dump on the
        // console control channel the smoketest harness drives (label is echoed back in
        // the log line so the harness can match request to stats). Built as a separate
        // chain from the cult root: cloud forbids placing a literal after the optional
        // target argument on the base debug command within one chain.
        commandManager.command(cultCommand
                .literal("debug")
                .literal("poolstats", Description.of("Log SectionPool deduplication statistics"))
                .permission("cult.debug")
                .required("label", StringParser.stringParser())
                .handler(this::handlePoolstats));

        // Register "debug validationblock <player> <x> <y> <z> <block|*>" — server-world
        // block mutation used as a controlled stimulus by the smoketest harness ('*'
        // maps to air). The "Cult validation block" log line is the harness's marker.
        // Arguments ride one greedy string (same shape as poolstats, which the harness
        // console channel drives reliably) and are split manually.
        commandManager.command(cultCommand
                .literal("debug")
                .literal("validationblock", Description.of("Set a world block for smoketest validation"))
                .permission("cult.debug")
                .required("args", StringParser.greedyStringParser())
                .handler(this::handleValidationBlock));

        // Register "debug validationpacketblock <player> <x> <y> <z> <block>" — ghost
        // block update sent only to the target client (no world mutation).
        commandManager.command(cultCommand
                .literal("debug")
                .literal("validationpacketblock", Description.of("Send a ghost block update for smoketest validation"))
                .permission("cult.debug")
                .required("args", StringParser.greedyStringParser())
                .handler(this::handleValidationPacketBlock));

        // Register command
        commandManager.command(debugCommand);
        commandManager.command(consoleDebugCommand);
    }

    private void handlePoolstats(@NotNull CommandContext<Sender> context) {
        SmoketestControlBridge.logSectionPoolStats(context.get("label"));
    }

    private void handleValidationBlock(@NotNull CommandContext<Sender> context) {
        applyValidationBlock(context, false);
    }

    private void handleValidationPacketBlock(@NotNull CommandContext<Sender> context) {
        applyValidationBlock(context, true);
    }

    private void applyValidationBlock(@NotNull CommandContext<Sender> context, boolean ghostOnly) {
        Sender sender = context.sender();
        String rawArgs = ((String) context.get("args")).trim();
        String[] tokens = rawArgs.split("\\s+");
        if (tokens.length != 5) {
            sender.sendMessage(Component.text(
                    "Usage: /cult debug " + (ghostOnly ? "validationpacketblock" : "validationblock")
                            + " <player> <x> <y> <z> <block|*>",
                    NamedTextColor.RED));
            return;
        }

        Player bukkitPlayer = Bukkit.getPlayerExact(tokens[0]);
        CultPlayer target = bukkitPlayer == null
                ? null
                : CultAPI.INSTANCE.getPlayerDataManager().getPlayer(bukkitPlayer.getUniqueId());
        if (target == null || target.bukkitPlayer == null) {
            sender.sendMessage(Component.text("Player is not available for validation block control", NamedTextColor.RED));
            return;
        }

        final int x;
        final int y;
        final int z;
        try {
            x = Integer.parseInt(tokens[1]);
            y = Integer.parseInt(tokens[2]);
            z = Integer.parseInt(tokens[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Coordinates must be whole numbers", NamedTextColor.RED));
            return;
        }

        String blockToken = tokens[4];
        BlockData blockData;
        try {
            blockData = "*".equals(blockToken)
                    ? Material.AIR.createBlockData()
                    : Bukkit.createBlockData(blockToken);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("Unknown block " + blockToken, NamedTextColor.RED));
            return;
        }

        String expectedId = "*".equals(blockToken) ? "minecraft:air" : blockToken;
        Location location = new Location(bukkitPlayer.getWorld(), x, y, z);
        if (ghostOnly) {
            bukkitPlayer.sendBlockChange(location, blockData);
            // The compensated world only reflects the ghost once the outbound packet
            // has been through Cult's send pipeline, so verify and log two ticks out.
            CultPlayer logTarget = target;
            CultAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().runDelayed(
                    CultAPI.INSTANCE.getGrimPlugin(),
                    () -> logValidationBlock(logTarget, x, y, z, expectedId, "packet"),
                    2L
            );
            return;
        }

        location.getBlock().setBlockData(blockData, false);
        logValidationBlock(target, x, y, z, expectedId, "world");
    }

    private static void logValidationBlock(CultPlayer target, int x, int y, int z, String expectedId, String mode) {
        String actualId = compensatedBlockId(target, x, y, z);
        LogUtil.info("Cult validation block player=" + target.user.getProfile().getName()
                + " x=" + x + " y=" + y + " z=" + z
                + " expected=" + expectedId
                + " actual=" + actualId
                + " matches=" + (expectedId.equals(actualId) ? "yes" : "no")
                + " mode=" + mode);
    }

    private static String compensatedBlockId(CultPlayer target, int x, int y, int z) {
        net.minecraft.world.level.block.state.BlockState state = target.compensatedWorld.getBlockStateAt(x, y, z);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private void handleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.getOrDefault("target", null);

        CultPlayer targetCultPlayer = parseTarget(sender, playerSelector == null ? sender : playerSelector.getSinglePlayer());
        if (targetCultPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        if (sender.isConsole()) {
            targetCultPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        } else if (sender.isPlayer()) {
            CultPlayer senderCultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
            if (senderCultPlayer == null) {
                sender.sendMessage(MessageUtil.getParsedComponent(sender, "sender-not-found", "%prefix% &cYou cannot be exempt to use this command!"));
                return;
            }
            targetCultPlayer.checkManager.getDebugHandler().toggleListener(senderCultPlayer.bukkitPlayer);
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(sender,
                    "run-as-player-or-console",
                    "%prefix% &cThis command can only be used by players or the console!")
            );
        }
    }

    private void handleConsoleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetName = context.getOrDefault("target", null);

        CultPlayer cultPlayer = parseTarget(sender, targetName.getSinglePlayer());
        if (cultPlayer == null) return;

        String requestedState = context.getOrDefault("state", "toggle");
        boolean isOutput = switch (requestedState.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "enable", "enabled" -> cultPlayer.checkManager.getDebugHandler().setConsoleOutput(true);
            case "off", "false", "disable", "disabled" -> cultPlayer.checkManager.getDebugHandler().setConsoleOutput(false);
            default -> cultPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        };
        String playerName = cultPlayer.user.getProfile().getName(); // Use user profile for name

        Component message = Component.text()
                .append(Component.text("Console output for ", NamedTextColor.GRAY))
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(Component.text(" is now ", NamedTextColor.GRAY))
                .append(Component.text(isOutput ? "enabled" : "disabled", NamedTextColor.WHITE))
                .build();

        sender.sendMessage(message);
    }

    private @Nullable CultPlayer parseTarget(@NotNull Sender sender, @Nullable Sender t) {
        if (sender.isConsole() && t == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "console-specify-target", "%prefix% &cYou must specify a target as the console!"));
            return null;
        }
        Sender target = t == null ? sender : t;

        CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(target.getUniqueId());
        if (cultPlayer == null) {
            PlatformPlayer platformPlayer = sender.getPlatformPlayer();
            User user = platformPlayer == null ? null : CultAPI.INSTANCE.getPlayerDataManager().getUser((Player) platformPlayer.getNative());
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));

            if (user == null) {
                sender.sendMessage(Component.text("Unknown user", NamedTextColor.RED));
            } else {
                boolean isExempt = CultAPI.INSTANCE.getPlayerDataManager().shouldCheck(user);
                if (!isExempt) {
                    sender.sendMessage(Component.text("User connection state: " + user.getConnectionState(), NamedTextColor.RED));
                }
            }
        }

        return cultPlayer;
    }
}
