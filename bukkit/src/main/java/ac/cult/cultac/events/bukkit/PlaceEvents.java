package ac.cult.cultac.events.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.player.CultPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public class PlaceEvents implements Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaceEvent(BlockPlaceEvent event) {
        Player placer = event.getPlayer();
        CultPlayer player = CultAPI.INSTANCE
                .getPlayerDataManager()
                .getPlayer(CultAPI.INSTANCE.getNetworkManager().getUser(placer.getUniqueId(), placer));
        if (player == null) return;

        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        if (event instanceof org.bukkit.event.block.BlockMultiPlaceEvent multi) {
            for (org.bukkit.block.BlockState state : multi.getReplacedBlockStates()) {
                mitigator.onServerBlockChange(
                        new ac.cult.cultac.protocol.value.BlockPos(state.getX(), state.getY(), state.getZ()),
                        event.isCancelled());
            }
        } else {
            var block = event.getBlock();
            mitigator.onServerBlockChange(
                    new ac.cult.cultac.protocol.value.BlockPos(block.getX(), block.getY(), block.getZ()),
                    event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEvent(PlayerBucketEmptyEvent event) {
        doEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEvent(PlayerBucketFillEvent event) {
        doEvent(event);
    }

    private void doEvent(PlayerBucketEvent event) {
        Player placer = event.getPlayer();
        CultPlayer player = CultAPI.INSTANCE
                .getPlayerDataManager()
                .getPlayer(CultAPI.INSTANCE.getNetworkManager().getUser(placer.getUniqueId(), placer));
        if (player == null) return;

        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        var block = event.getBlock();
        mitigator.onServerBlockChange(
                new ac.cult.cultac.protocol.value.BlockPos(block.getX(), block.getY(), block.getZ()),
                event.isCancelled());
    }
}
