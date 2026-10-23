package ac.cult.cultac.command.commands;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMod;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMods;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.manager.player.SmoketestControlBridge;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CultDebug implements BuildableCommand {

    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> cultCommand = BuildableCommand.root(commandManager);

        Command.Builder<Sender> debugCommand = cultCommand
                .literal("debug", Description.of("Toggle debug output for a player"))
                .permission("cult.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebug);

        commandManager.command(cultCommand
                .literal("debug")
                .literal("rewind", Description.of("Toggle Bedrock rewind positions and offsets"))
                .permission("cult.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugRewind));

        commandManager.command(cultCommand
                .literal("debug")
                .literal("places", Description.of("Toggle processed block-state changes for you or a player"))
                .permission("cult.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugPlaces));

        commandManager.command(cultCommand
                .literal("debug")
                .literal("channels", Description.of("List the plugin channels a player's client opened"))
                .permission("cult.debug")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugChannels));

        commandManager.command(cultCommand
                .literal("debug")
                .literal("mods", Description.of("List a player's Lunar Client mods reported through Apollo"))
                .permission("cult.debug")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugMods));

        commandManager.command(cultCommand
                .literal("debug")
                .literal("visibility", Description.of("Watch visibility changes of your next placed block"))
                .permission("cult.debug")
                .handler(this::handleDebugVisibility));

        commandManager.command(cultCommand
                .literal("debug")
                .literal("itemslow", Description.of("Toggle item-use balance and server-view use tracking"))
                .permission("cult.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugItemSlow));

        Command.Builder<Sender> proxyTicksCommand = cultCommand
                .literal("debug")
                .literal("proxyticks", Description.of("Show observed proxy tick-end hits and misses"))
                .permission("cult.debug");
        commandManager.command(proxyTicksCommand
                .optional("target", arguments.singlePlayerSelectorParser())
                .flag(commandManager.flagBuilder("reset"))
                .handler(context ->
                        handleDebugProxyTicks(context, context.flags().hasFlag("reset"))));
        // Cloud normally parses flags after positional arguments. This literal also allows
        // omitting the player, without changing flag placement for unrelated command trees.
        commandManager.command(proxyTicksCommand
                .literal("--reset")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(context -> handleDebugProxyTicks(context, true)));

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

        // Register "debug validationblock [player] <x> <y> <z> <block|*>" — server-world
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

        // Register "debug validationpacketblock [player] <x> <y> <z> <block> [silent]" — ghost
        // block update sent only to the target client (no world mutation). The player defaults to
        // the sender; coordinates accept ~ relatives to the target; "silent" also hides the
        // update from Cult's own model.
        commandManager.command(cultCommand
                .literal("debug")
                .literal("validationpacketblock", Description.of("Send a ghost block update for smoketest validation"))
                .permission("cult.debug")
                .required("args", StringParser.greedyStringParser())
                .handler(this::handleValidationPacketBlock));

        // Register "debug ignoreinteract <seconds>" — drops your block, item, entity and inventory
        // interactions so your local predictions desync, then resyncs blocks and inventory from the
        // server views when the time ends (0 ends an active pause now).
        commandManager.command(cultCommand
                .literal("debug")
                .literal("ignoreinteract", Description.of("Ignore your interactions, then resync"))
                .permission("cult.debug")
                .required("seconds", DoubleParser.doubleParser(0, 300))
                .handler(this::handleIgnoreInteract));

        commandManager.command(debugCommand);
        commandManager.command(consoleDebugCommand);
    }

    private void handleDebugRewind(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector selector = context.getOrDefault("target", null);
        CultPlayer target = parseTarget(sender, selector == null ? null : selector.getSinglePlayer());
        if (target == null) return;
        if (!target.isBedrockMovement()) {
            sender.sendMessage(Component.text("Rewind debugging requires a Bedrock player.", NamedTextColor.RED));
            return;
        }
        boolean enabled = target.checkManager.getDebugHandler().toggleRewindListener(sender);
        sender.sendMessage(Component.text(
                (enabled ? "Enabled" : "Disabled") + " rewind debugging for " + target.getName()
                        + (enabled ? ": c=client, s=server, o=c-s" : ""),
                NamedTextColor.GRAY));
    }

    private void handleDebugItemSlow(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector selector = context.getOrDefault("target", null);
        CultPlayer target = parseTarget(sender, selector == null ? null : selector.getSinglePlayer());
        if (target == null) return;
        if (!target.usesModernJavaItemUse()) {
            sender.sendMessage(
                    Component.text("Item slowdown debugging requires a Java 1.21.2+ player.", NamedTextColor.RED));
            return;
        }
        String stopCommand =
                CloudCommandService.command("debug itemslow") + (selector == null ? "" : " " + target.getName());
        target.runSafely(() -> {
            boolean enabled = target.checkManager.getDebugHandler().toggleItemSlowListener(sender);
            sender.sendMessage(Component.text(
                    (enabled ? "Enabled" : "Disabled") + " item slowdown debugging for " + target.getName()
                            + (enabled
                                    ? ": " + target.itemUseBurst.describe()
                                            + ". Positive balance is unpaid use ticks; VIEW lines trace the"
                                            + " server-view use; run " + stopCommand + " again to stop."
                                    : "."),
                    NamedTextColor.GRAY));
        });
    }

    private void handleDebugProxyTicks(@NotNull CommandContext<Sender> context, boolean reset) {
        Sender sender = context.sender();
        PlayerSelector selector = context.getOrDefault("target", null);
        CultPlayer target = parseTarget(sender, selector == null ? null : selector.getSinglePlayer());
        if (target == null) return;
        target.runSafely(() -> {
            var ticks = target.backendTicks();
            if (ticks == null) {
                sender.sendMessage(
                        Component.text("Proxy tick diagnostics are only available on Velocity.", NamedTextColor.RED));
                return;
            }
            if (reset) {
                ticks.resetTickEndStats();
                sender.sendMessage(Component.text(
                        "Reset proxy tick diagnostics for " + target.getName() + ".", NamedTextColor.GRAY));
                return;
            }
            var stats = ticks.tickEndStats();
            String counts = stats.observedWaves() == 0
                    ? "hit=n/a, missed=n/a (no completed tracking waves yet)"
                    : String.format(
                            Locale.ROOT,
                            "hit=%.2f%% (%d), missed=%.2f%% (%d), observed waves=%d",
                            stats.hitPercent(),
                            stats.hits(),
                            stats.missPercent(),
                            stats.misses(),
                            stats.observedWaves());
            sender.sendMessage(Component.text(
                    "Observed proxy ticks for " + target.getName() + ": " + counts + ", emitted ends=" + stats.emitted()
                            + ", mode=" + ticks.mode(),
                    NamedTextColor.GRAY));
        });
    }

    private void handleDebugChannels(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector selector = context.get("target");
        CultPlayer target = parseTarget(sender, selector.getSinglePlayer());
        if (target == null) return;
        target.runSafely(() -> {
            var channels = target.pluginChannelManager.channels();
            if (channels.isEmpty()) {
                sender.sendMessage(
                        line(target, "debug-channels-none", "%prefix% &f%player% &bhas no plugin channels open"));
                return;
            }
            sender.sendMessage(line(
                    target,
                    "debug-channels-header",
                    "%prefix% &b%player% has &f%count% &bplugin channels open",
                    "%count%",
                    Integer.toString(channels.size())));
            for (String channel : channels) {
                sender.sendMessage(line(target, "debug-channels-entry", "&8- &f%channel%", "%channel%", channel));
            }
        });
    }

    private void handleDebugMods(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector selector = context.get("target");
        CultPlayer target = parseTarget(sender, selector.getSinglePlayer());
        if (target == null) return;
        PlatformPlayer platformPlayer = target.platformPlayer;
        var request = platformPlayer == null
                ? java.util.concurrent.CompletableFuture.<ClientMods>completedFuture(null)
                : platformPlayer.requestClientMods();
        request.whenComplete((fresh, failure) -> {
            if (fresh != null) target.clientMods = fresh;
            sendMods(sender, target, fresh != null ? fresh : target.clientMods);
        });
    }

    private static void sendMods(Sender sender, CultPlayer target, @Nullable ClientMods mods) {
        if (mods == null) {
            sender.sendMessage(line(
                    target,
                    "debug-mods-unavailable",
                    "%prefix% &cNo Apollo data for &f%player%&c; their server needs Apollo-Bukkit and the CultAC"
                            + " companion"));
            return;
        }
        if (!mods.lunar()) {
            sender.sendMessage(line(
                    target,
                    "debug-mods-not-lunar",
                    "%prefix% &f%player% &bis not connected to Apollo; not a Lunar Client player"));
            return;
        }
        sender.sendMessage(line(
                target,
                "debug-mods-header",
                "%prefix% &b%player% has &f%count% &bLunar Client mods",
                "%count%",
                Integer.toString(mods.mods().size())));
        var byType = new java.util.TreeMap<String, java.util.List<ClientMod>>();
        for (ClientMod mod : mods.mods()) {
            byType.computeIfAbsent(mod.type(), ignored -> new java.util.ArrayList<>())
                    .add(mod);
        }
        for (var group : byType.entrySet()) {
            sender.sendMessage(line(target, "debug-mods-type", "&b%type%:", "%type%", group.getKey()));
            group.getValue()
                    .sort(java.util.Comparator.comparing(mod -> modName(mod).toLowerCase(Locale.ROOT)));
            for (ClientMod mod : group.getValue()) {
                sender.sendMessage(line(
                        target,
                        "debug-mods-entry",
                        "&8- &f%name% &7(%mod%) &8%version%",
                        "%name%",
                        modName(mod),
                        "%mod%",
                        mod.id(),
                        "%version%",
                        mod.version()));
            }
        }
    }

    private static String modName(ClientMod mod) {
        return mod.name().isEmpty() ? mod.id() : mod.name();
    }

    private static final java.util.regex.Pattern LINE_PLACEHOLDER = java.util.regex.Pattern.compile("%[a-z_]+%");

    /** The configured line for {@code key} about {@code target}; client-supplied values cannot inject tags. */
    private static Component line(CultPlayer target, String key, String fallback, String... values) {
        String message = MessageUtil.replacePlaceholders(
                target, CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback));
        var replacements = new java.util.HashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) replacements.put(values[i], values[i + 1]);
        // One pass, so a value holding a placeholder name is shown as it is.
        var matcher = LINE_PLACEHOLDER.matcher(message);
        var replaced = new StringBuilder(message.length());
        while (matcher.find()) {
            String value = replacements.get(matcher.group());
            matcher.appendReplacement(
                    replaced,
                    java.util.regex.Matcher.quoteReplacement(
                            value == null ? matcher.group() : MessageUtil.miniMessageSafe(value)));
        }
        return MessageUtil.miniMessage(matcher.appendTail(replaced).toString());
    }

    private void handleDebugPlaces(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector selector = context.getOrDefault("target", null);
        CultPlayer player = parseTarget(sender, selector == null ? null : selector.getSinglePlayer());
        if (player == null) return;
        player.runSafely(() -> player.compensatedWorld.toggleBlockChangeDebug());
    }

    private void handleDebugVisibility(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        if (!sender.isPlayer()) {
            sender.sendMessage(Component.text(
                    "Run " + CloudCommandService.command("debug visibility") + " in-game.", NamedTextColor.RED));
            return;
        }
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (player == null) {
            sender.sendMessage(Component.text("Your player is exempt or not tracked by Cult.", NamedTextColor.RED));
            return;
        }
        player.runSafely(() -> player.visibilityDebug.toggle());
    }

    private void handlePoolstats(@NotNull CommandContext<Sender> context) {
        SmoketestControlBridge.logSectionPoolStats(context.get("label"));
    }

    private void handleIgnoreInteract(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        CultPlayer target = senderPlayer(sender, "ignoreinteract");
        if (target == null) return;
        double seconds = context.get("seconds");
        long millis = Math.round(seconds * 1000);
        target.runSafely(() -> target.interactionPause.start(millis));
        sender.sendMessage(Component.text(
                millis == 0
                        ? "Resyncing your blocks and inventory now"
                        : "Ignoring your interactions for " + seconds + "s, then resyncing",
                NamedTextColor.GRAY));
    }

    /** The command sender's own tracked player, or null after telling them why not. */
    private static @Nullable CultPlayer senderPlayer(Sender sender, String command) {
        if (!sender.isPlayer()) {
            sender.sendMessage(Component.text(
                    "Run " + CloudCommandService.command("debug " + command) + " in-game.", NamedTextColor.RED));
            return null;
        }
        CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
        if (player == null) {
            sender.sendMessage(Component.text("Your player is exempt or not tracked by Cult.", NamedTextColor.RED));
        }
        return player;
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
        var args = new java.util.ArrayList<>(java.util.List.of(rawArgs.split("\\s+")));
        boolean silent = ghostOnly && args.size() > 4 && args.getLast().equalsIgnoreCase("silent");
        if (silent) args.removeLast();
        String command = ghostOnly ? "validationpacketblock" : "validationblock";
        if (args.size() != 4 && args.size() != 5) {
            sender.sendMessage(Component.text(
                    "Usage: " + CloudCommandService.command("debug " + command) + " [player] <x> <y> <z> <block|*>"
                            + (ghostOnly ? " [silent]" : ""),
                    NamedTextColor.RED));
            return;
        }

        // The player is optional: you, unless named (the smoketest console always names one).
        final CultPlayer target;
        if (args.size() == 5) {
            PlatformPlayer named = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromName(args.removeFirst());
            target = named == null
                    ? null
                    : CultAPI.INSTANCE.getPlayerDataManager().getPlayer(named.getUniqueId());
            if (target == null || target.platformPlayer == null) {
                sender.sendMessage(
                        Component.text("Player is not available for validation block control", NamedTextColor.RED));
                return;
            }
        } else {
            target = senderPlayer(sender, command);
            if (target == null || target.platformPlayer == null) return;
        }
        PlatformPlayer platformPlayer = target.platformPlayer;

        if (!ghostOnly && !platformPlayer.hasCapability(ServerCapability.VALIDATION_BLOCK)) {
            sender.sendMessage(Component.text(
                    "This platform cannot mutate server blocks; use validationpacketblock", NamedTextColor.RED));
            return;
        }

        final Coordinate x;
        final Coordinate y;
        final Coordinate z;
        try {
            x = Coordinate.parse(args.get(0));
            y = Coordinate.parse(args.get(1));
            z = Coordinate.parse(args.get(2));
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text(
                    "Coordinates must be whole numbers or ~ relatives (~, ~2, ~-1.5)", NamedTextColor.RED));
            return;
        }

        String blockToken = args.get(3);
        // As in vanilla block arguments, an id without a namespace is a minecraft: block.
        boolean namespaced = blockToken.split("\\[", 2)[0].contains(":");
        String expectedId =
                "*".equals(blockToken) ? "minecraft:air" : namespaced ? blockToken : "minecraft:" + blockToken;
        final int state;
        try {
            state = parseBlockState(expectedId);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("Unknown block " + blockToken, NamedTextColor.RED));
            return;
        }

        // Relatives use the target's position as Cult last accepted it, on its connection owner.
        target.runSafely(() -> {
            var pos = new ac.cult.cultac.protocol.value.BlockPos(
                    x.resolve(target.x), y.resolve(target.y), z.resolve(target.z));
            if (ghostOnly) {
                sendValidationPacketBlock(sender, target, pos, state, expectedId, silent);
            } else {
                CultAPI.INSTANCE
                        .getPlatformServer()
                        .applyValidationBlock(
                                platformPlayer,
                                pos.getX(),
                                pos.getY(),
                                pos.getZ(),
                                expectedId,
                                () -> target.runSafely(() -> logValidationBlock(
                                        target, pos.getX(), pos.getY(), pos.getZ(), expectedId, "world")));
            }
        });
    }

    /**
     * Sends the target a block update the server never sent. Normally it passes through Cult like any
     * plugin-sent block. Silent, it bypasses Cult's packet handling entirely: only the client knows
     * about it, as with a real ghost block.
     */
    private static void sendValidationPacketBlock(
            Sender sender,
            CultPlayer target,
            ac.cult.cultac.protocol.value.BlockPos pos,
            int state,
            String expectedId,
            boolean silent) {
        if (target.compensatedWorld.getServerSentBlockStateIdAt(pos) == CompensatedWorld.UNKNOWN_SERVER_STATE) {
            // The client ignores block updates for chunks it has not loaded.
            sender.sendMessage(Component.text("That block's chunk is not loaded for the player", NamedTextColor.RED));
            return;
        }
        CultPlayer.TrackedTransaction transaction =
                target.canSendPlayPackets() ? target.createTrackedTransactionPacketForBundle() : null;
        if (transaction == null) {
            sender.sendMessage(Component.text("Player is not in play yet", NamedTextColor.RED));
            return;
        }
        target.latencyUtils.addRealTimeTask(
                transaction.transaction(),
                () -> logValidationBlock(
                        target, pos.getX(), pos.getY(), pos.getZ(), expectedId, silent ? "silent" : "packet"));
        // Silent writes skip Cult's send handlers; the ping proves the client applied the block.
        target.user.write(
                java.util.List.of(
                        new ac.cult.cultac.network.CultWrite(
                                new ac.cult.cultac.network.packet.WorldPackets.BlockUpdate(pos, state), silent),
                        transaction.packet()),
                true);
        sender.sendMessage(Component.text(
                "Sent " + (silent ? "silent " : "") + "ghost " + expectedId + " to " + target.getName() + " at "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ(),
                NamedTextColor.GRAY));
    }

    /** An absolute block coordinate, or {@code ~offset} relative to the target's position. */
    private record Coordinate(boolean relative, double value) {
        static Coordinate parse(String token) {
            if (!token.startsWith("~")) return new Coordinate(false, Integer.parseInt(token));
            return new Coordinate(true, token.length() == 1 ? 0 : Double.parseDouble(token.substring(1)));
        }

        /** As BlockPosArgument: the block containing the base position plus the offset. */
        int resolve(double base) {
            return relative ? (int) Math.floor(base + value) : (int) value;
        }
    }

    /** Parses {@code namespace:block[property=value,...]} into a model block state. */
    private static int parseBlockState(String text) {
        var registry = DataTables.defaults().registry();
        int properties = text.indexOf('[');
        String key = properties < 0 ? text : text.substring(0, properties);
        int state = registry.block(key).defaultState();
        if (properties < 0) return state;
        if (!text.endsWith("]")) throw new IllegalArgumentException("Unknown block " + text);
        String body = text.substring(properties + 1, text.length() - 1);
        if (body.isEmpty()) return state;
        for (String assignment : body.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Unknown block " + text);
            state = registry.with(state, assignment.substring(0, equals), assignment.substring(equals + 1));
        }
        return state;
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
        int state = target.compensatedWorld.getBlockStateIdAt(x, y, z);
        return DataTables.defaults().registry().block(state).key();
    }

    private void handleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.getOrDefault("target", null);

        CultPlayer targetCultPlayer =
                parseTarget(sender, playerSelector == null ? sender : playerSelector.getSinglePlayer());
        if (targetCultPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        if (sender.isConsole()) {
            targetCultPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        } else if (sender.isPlayer()) {
            CultPlayer senderCultPlayer =
                    CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId());
            if (senderCultPlayer == null) {
                sender.sendMessage(MessageUtil.getParsedComponent(
                        sender, "sender-not-found", "%prefix% &cYou cannot be exempt to use this command!"));
                return;
            }
            targetCultPlayer.checkManager.getDebugHandler().toggleListener(senderCultPlayer.platformPlayer);
        } else {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "run-as-player-or-console",
                    "%prefix% &cThis command can only be used by players or the console!"));
        }
    }

    private void handleConsoleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetName = context.getOrDefault("target", null);

        CultPlayer cultPlayer = parseTarget(sender, targetName.getSinglePlayer());
        if (cultPlayer == null) return;

        String requestedState = context.getOrDefault("state", "toggle");
        boolean isOutput = switch (requestedState.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled" ->
                cultPlayer.checkManager.getDebugHandler().setConsoleOutput(true);
            case "off", "false", "disable", "disabled" ->
                cultPlayer.checkManager.getDebugHandler().setConsoleOutput(false);
            default -> cultPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        };
        String playerName = cultPlayer.user.getProfile().getName();

        Component message = Component.text()
                .append(Component.text("Console output for ", NamedTextColor.GRAY))
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(Component.text(" is now ", NamedTextColor.GRAY))
                .append(Component.text(isOutput ? "enabled" : "disabled", NamedTextColor.WHITE))
                .asComponent();

        sender.sendMessage(message);
    }

    private @Nullable CultPlayer parseTarget(@NotNull Sender sender, @Nullable Sender t) {
        if (sender.isConsole() && t == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "console-specify-target", "%prefix% &cYou must specify a target as the console!"));
            return null;
        }
        Sender target = t == null ? sender : t;

        CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(target.getUniqueId());
        if (cultPlayer == null) {
            PlatformPlayer platformPlayer = sender.getPlatformPlayer();
            User user = platformPlayer == null
                    ? null
                    : CultAPI.INSTANCE.getPlayerDataManager().getUser(platformPlayer);
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));

            if (user == null) {
                sender.sendMessage(Component.text("Unknown user", NamedTextColor.RED));
            } else {
                boolean isExempt = CultAPI.INSTANCE.getPlayerDataManager().shouldCheck(user);
                if (!isExempt) {
                    sender.sendMessage(
                            Component.text("User connection state: " + user.getConnectionState(), NamedTextColor.RED));
                }
            }
        }

        return cultPlayer;
    }
}
