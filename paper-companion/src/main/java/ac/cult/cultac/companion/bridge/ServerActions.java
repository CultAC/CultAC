package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloseInventory;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ConsoleExec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.SetGameMode;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Teleport;
import ac.cult.cultac.bridge.protocol.BridgeMessage.TeleportToPlayer;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ValidationBlock;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Performs the proxy's server actions on the main thread; every request is answered with a RESULT. */
final class ServerActions implements CompanionMessageHandler {
    private final Plugin plugin;
    private final BridgeService service;

    ServerActions(Plugin plugin, BridgeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public void onMessage(CompanionSession session, BridgeMessage message) {
        switch (message) {
            case ClearActiveItem clear ->
                onlyInPacketOrder(session, player -> {
                    if (usingMatching(player, clear)) player.clearActiveItem();
                });
            case SetGameMode mode -> {
                GameMode gameMode = gameMode(mode.mode());
                if (gameMode != null) act(session, player -> player.setGameMode(gameMode));
            }
            case CloseInventory ignored -> inPacketOrder(session, Player::closeInventory);
            case Teleport teleport ->
                request(session, teleport.requestId(), player -> {
                    World world = Bukkit.getWorld(teleport.world());
                    return world == null
                            ? CompletableFuture.completedFuture(false)
                            : player.teleportAsync(new Location(
                                    world, teleport.x(), teleport.y(), teleport.z(), teleport.yaw(), teleport.pitch()));
                });
            case TeleportToPlayer teleport ->
                request(session, teleport.requestId(), player -> {
                    Player target = Bukkit.getPlayer(teleport.target());
                    return target == null
                            ? CompletableFuture.completedFuture(false)
                            : player.teleportAsync(target.getLocation());
                });
            case ValidationBlock block -> request(session, block.requestId(), player -> validationBlock(player, block));
            case ConsoleExec exec -> consoleExec(session, exec);
            case PlayerState query -> playerState(session, query);
            default -> {}
        }
    }

    /** Runs the action after the game packets that preceded the frame and before those that follow it. */
    private void inPacketOrder(CompanionSession session, Consumer<Player> action) {
        if (!onlyInPacketOrder(session, action)) act(session, action);
    }

    /** Like {@link #inPacketOrder}, but drops the action when the connection cannot queue it; false then. */
    private boolean onlyInPacketOrder(CompanionSession session, Consumer<Player> action) {
        return session.runInPacketOrder(() -> perform(session, action));
    }

    private void act(CompanionSession session, Consumer<Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> perform(session, action));
    }

    private void perform(CompanionSession session, Consumer<Player> action) {
        Player player = session.player();
        if (player == null || !player.isOnline()) return;
        try {
            action.accept(player);
        } catch (Throwable failure) {
            service.logger().log(Level.WARNING, "Bridge action failed for " + player.getName(), failure);
        }
    }

    private void request(CompanionSession session, int requestId, Function<Player, CompletableFuture<Boolean>> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = session.player();
            if (player == null || !player.isOnline()) {
                session.send(new Result(requestId, false));
                return;
            }
            CompletableFuture<Boolean> outcome;
            try {
                outcome = action.apply(player);
            } catch (Throwable failure) {
                service.logger().log(Level.WARNING, "Bridge request failed for " + player.getName(), failure);
                session.send(new Result(requestId, false));
                return;
            }
            outcome.whenComplete((success, failure) -> {
                if (failure != null)
                    service.logger().log(Level.WARNING, "Bridge request failed for " + player.getName(), failure);
                session.send(new Result(requestId, failure == null && Boolean.TRUE.equals(success)));
            });
        });
    }

    private void consoleExec(CompanionSession session, ConsoleExec exec) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean success;
            try {
                success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), exec.command());
            } catch (Throwable failure) {
                service.logger().log(Level.WARNING, "Proxy console command failed: " + exec.command(), failure);
                success = false;
            }
            session.send(new Result(exec.requestId(), success));
        });
    }

    private void playerState(CompanionSession session, PlayerState query) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = session.player();
            if (player == null || !player.isOnline()) {
                session.send(new Result(query.requestId(), false));
                return;
            }
            PlayerStateResult state;
            try {
                state = state(query.requestId(), player.getGameMode(), player.getLocation());
            } catch (Throwable failure) {
                service.logger().log(Level.WARNING, "Bridge state query failed for " + player.getName(), failure);
                session.send(new Result(query.requestId(), false));
                return;
            }
            session.send(state);
        });
    }

    private static boolean usingMatching(Player player, ClearActiveItem clear) {
        if (!player.isHandRaised()) return false;
        ItemStack item = player.getActiveItem();
        String key = item.getType().isAir() ? null : item.getType().getKey().asString();
        return matches(clear, player.getActiveItemHand(), key);
    }

    /** Whether a use of {@code activeKey} in {@code activeHand} is the one the clear names; null means no use. */
    static boolean matches(ClearActiveItem clear, @Nullable EquipmentSlot activeHand, @Nullable String activeKey) {
        EquipmentSlot expected = switch (clear.hand()) {
            case ClearActiveItem.MAIN_HAND -> EquipmentSlot.HAND;
            case ClearActiveItem.OFF_HAND -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
        return expected != null && activeHand == expected && clear.itemKey().equals(activeKey);
    }

    static PlayerStateResult state(int requestId, GameMode gameMode, Location location) {
        return new PlayerStateResult(
                requestId,
                wire(gameMode),
                location.getWorld().getUID(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    static @Nullable GameMode gameMode(int wire) {
        return switch (wire) {
            case SetGameMode.SURVIVAL -> GameMode.SURVIVAL;
            case SetGameMode.CREATIVE -> GameMode.CREATIVE;
            case SetGameMode.ADVENTURE -> GameMode.ADVENTURE;
            case SetGameMode.SPECTATOR -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    static int wire(GameMode gameMode) {
        return switch (gameMode) {
            case SURVIVAL -> SetGameMode.SURVIVAL;
            case CREATIVE -> SetGameMode.CREATIVE;
            case ADVENTURE -> SetGameMode.ADVENTURE;
            case SPECTATOR -> SetGameMode.SPECTATOR;
        };
    }

    private CompletableFuture<Boolean> validationBlock(Player player, ValidationBlock block) {
        BlockData data = Bukkit.createBlockData(block.blockState());
        World world = player.getWorld();
        Location location = new Location(world, block.x(), block.y(), block.z());
        if (!block.packetOnly()) {
            location.getBlock().setBlockData(data, false);
            return CompletableFuture.completedFuture(true);
        }
        player.sendBlockChange(location, data);
        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> applied.complete(true), 2L);
        return applied;
    }
}
