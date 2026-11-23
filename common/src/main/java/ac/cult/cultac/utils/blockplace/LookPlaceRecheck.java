package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.latency.BlockSimulatorInventory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServerboundUseItemOnPacket carries no rotation, and a hit straight above or below the eye gives no yaw either. Such
 * a use-on is predicted with the last yaw sent. Minecraft#handleKeybinds runs it before LocalPlayer#sendPosition, and
 * the camera only turns between client ticks, so the yaw it was made with is the one this client tick sends: in a
 * ServerboundUseItemPacket of the same click, or in the tick's movement. A use-on whose effects read the yaw is
 * predicted again once that yaw is known. Owner-thread only.
 */
public final class LookPlaceRecheck {
    private final CultPlayer player;
    private boolean knowsYaw;
    private float yaw;
    private Pending pending;

    public LookPlaceRecheck(CultPlayer player) {
        this.player = player;
    }

    /** Whether a packet of this client tick has carried the yaw its use-ons are made with. */
    boolean knowsYaw() {
        return knowsYaw;
    }

    float yaw() {
        return yaw;
    }

    /** A use-on predicted with the last yaw sent, before it is applied. */
    void predicted(BlockPlace place, SimAction.UseOn action, ClientBlockActions.Prediction prediction, float usedYaw) {
        pending = null;
        if (player.isBedrockMovement()) return;
        var result = prediction.result();
        if (result.decline() != null || !result.player().yawRead()) return;
        // Only its blocks and stacks are put back; an action with other effects keeps this prediction.
        if (result.player().activeUse() != null
                || result.player().foodLevel()
                        != prediction.input().player().state().foodLevel()
                || !ClientBlockActions.cooldownChanges(prediction).isEmpty()) return;

        var world = player.compensatedWorld;
        var blocks = new LinkedHashMap<BlockPos, Integer>();
        for (var write : prediction.writes()) {
            BlockPos pos = write.getKey();
            if (pos.getY() < world.getMinHeight() || pos.getY() >= world.getMaxHeight()) continue;
            blocks.putIfAbsent(pos, world.getBlockStateIdAt(pos));
        }
        var blockEntities = new ArrayList<BlockPos>();
        for (var changed : result.blockEntities().keySet()) {
            BlockPos pos = new BlockPos(changed.x(), changed.y(), changed.z());
            if (world.storedClientBlockEntityData(pos) != null) return;
            blockEntities.add(pos);
        }
        var storage = player.getInventory().inventory.getInventoryStorage();
        var stacks = new HashMap<Integer, SimItemStack>();
        for (int slot : prediction.inventory().keySet()) {
            int storageSlot = BlockSimulatorInventory.storageSlot(slot);
            stacks.put(storageSlot, storage.getItem(storageSlot));
        }
        pending = new Pending(
                place, action, prediction, usedYaw, world.clientPredictionSequence(), blocks, blockEntities, stacks);
    }

    /** The use-on was withheld from the server, so its client is sent the server's blocks and stacks. */
    public void withheld(ServerboundUseItemOn packet) {
        if (pending != null && pending.place.sequence == packet.sequence()) pending.withheld = packet;
    }

    /** A packet of this client tick carries the yaw its use-ons were made with. */
    public void yawKnown(float yaw) {
        if (player.isBedrockMovement()) return;
        Pending place = pending;
        pending = null;
        knowsYaw = true;
        this.yaw = yaw;
        // Another action predicted since then read the world this one left.
        if (place == null
                || place.yaw == yaw
                || place.predictionSequence != player.compensatedWorld.clientPredictionSequence()) return;
        var again = PlaceHandler.redoQueuedUseItemOn(player, place.place.sequence, () -> {
            place.restore(player);
            return ClientBlockActions.useOnAgain(player, place.place, place.action, place.prediction, yaw);
        });
        // Observe-only forwarded the action, so the server answers it.
        if (place.withheld != null && !player.isObserveOnly())
            player.blockActionReconciler.rejectPlace(place.withheld, again);
    }

    /** LocalPlayer#sendPosition or Minecraft#tick ended the client tick's actions. */
    public void tickEnded() {
        pending = null;
        knowsYaw = false;
    }

    private static final class Pending {
        final BlockPlace place;
        final SimAction.UseOn action;
        final ClientBlockActions.Prediction prediction;
        final float yaw;
        final int predictionSequence;
        final Map<BlockPos, Integer> blocks;
        final List<BlockPos> blockEntities;
        final Map<Integer, SimItemStack> stacks;
        ServerboundUseItemOn withheld;

        Pending(
                BlockPlace place,
                SimAction.UseOn action,
                ClientBlockActions.Prediction prediction,
                float yaw,
                int predictionSequence,
                Map<BlockPos, Integer> blocks,
                List<BlockPos> blockEntities,
                Map<Integer, SimItemStack> stacks) {
            this.place = place;
            this.action = action;
            this.prediction = prediction;
            this.yaw = yaw;
            this.predictionSequence = predictionSequence;
            this.blocks = blocks;
            this.blockEntities = blockEntities;
            this.stacks = stacks;
        }

        void restore(CultPlayer player) {
            var world = player.compensatedWorld;
            blocks.forEach(world::updateBlock);
            for (BlockPos pos : blockEntities) world.applyPredictedBlockEntityData(pos, null);
            var storage = player.getInventory().inventory.getInventoryStorage();
            stacks.forEach(storage::setItem);
            // The listener marked both clicked cells before the first prediction wrote over them.
            BlockPos against = place.getPlacedAgainstBlockLocation();
            BlockPos normal = place.getNormalBlockFace();
            BlockPos relative = against.offset(normal.getX(), normal.getY(), normal.getZ());
            var ghosts = player.getGhostBlockMitigator();
            ghosts.handlePseudoPlace(new GhostBlock(against, place.getItemStack()));
            ghosts.handlePseudoPlace(new GhostBlock(relative, place.getItemStack()));
        }
    }
}
