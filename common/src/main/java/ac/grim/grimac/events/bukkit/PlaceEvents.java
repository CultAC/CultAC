package ac.grim.grimac.events.bukkit;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.movement.GhostBlockMitigator;
import ac.grim.grimac.player.GrimPlayer;
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
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(placer);
        if (player == null) return;

        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        mitigator.onServerValidBlockPlace(event);
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
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(placer);
        if (player == null) return;

        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        mitigator.onServerValidBucketUse(event);
    }
}
