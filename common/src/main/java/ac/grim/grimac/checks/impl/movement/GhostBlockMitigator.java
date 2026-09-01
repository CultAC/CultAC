package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.events.packets.patch.ResyncWorldUtil;
import ac.grim.grimac.manager.player.SetbackTeleportUtil;
import ac.grim.grimac.manager.tick.Tickable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.blockplace.GhostBlock;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import org.bukkit.block.data.BlockData;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import org.bukkit.Material;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEvent;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


// Agreement with bukkit is more important than agreement with the client (for buckets)
//
// Additionally, we don't give a shit if we setback too much here accidentally
// We already would need to setback. lol. so it doesn't matter.
//
// Let ghost blocks persist for a single tick, before checking the server for the correct state
public class GhostBlockMitigator extends GrimProcessor implements PostPredictionListener, Tickable {

    public GhostBlockMitigator(GrimPlayer player) {
        super(player);
    }

    // If a player's movement requires colliding with one of these blocks to be valid, mark it as a suspicious block
    //
    // Keep this data until the server has confirmed these places - where we can then remove the data
    //
    // If after the next tick end event, the server fails to confirm these places,
    // then send the player the blocks at this location (pre-1.19), then mark them as no longer ghost blocks
    //
    // once fully confirmed
    public final Map<BlockPos, GhostData> unknownStuff = new ConcurrentHashMap<>();

    // If a player uses a bucket in flags in the area of the bucket
    // Setback the player without flagging as cheating, remove this bucket place, then resend slightly around player
    // This should be persistent, except on chunk unloads (prevent slight memory leak)
    // Make it a set to cap the limit of this list, to stop crashes
    private final Set<BlockPos> bucketUseLocations = new HashSet<>();
    private final Set<GhostBlock> pseudoPlaces = new HashSet<>();


    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) return;

        PredictionResult result = predictionComplete.getPredictionResult();

        if (result.getFlagSeverity() > 0 && (!unknownStuff.isEmpty() || !bucketUseLocations.isEmpty() || !pseudoPlaces.isEmpty())) {
            // Check if intersecting with one of the player's ghost blocks
            Vec3 from = result.getSimulationContext().getStart();
            Vec3 to = result.getSimulationContext().getEnd();

            // TODO: Support vehicle hitbox size...
            SimpleCollisionBox movement = new SimpleCollisionBox(from, to);
            movement.expandMax(0.33, 1.83, 0.33);
            movement.expandMin(-0.33, -0.03, -0.33);

            final SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
            for (BlockPos fuckedLoc : bucketUseLocations) {
                SimpleCollisionBox bucketBox = new SimpleCollisionBox(fuckedLoc);
                if (movement.isIntersected(bucketBox)) {
                    result.exempt();
                    setbackUtil.executeForceResync("bucket");
                    break;
                }
            }

            for (GhostBlock ghostBlock : pseudoPlaces) {
                // ignore end crystals
                if (ghostBlock.getItemUsed() != null && ghostBlock.getItemUsed().getType() == Material.END_CRYSTAL) continue;

                final SimpleCollisionBox place = new SimpleCollisionBox(ghostBlock.getPosition()).expandMax(0, 0.5, 0);
                if (place.isIntersected(movement)) {
                    result.exempt();
                    setbackUtil.executeForceResync("places");
                    break;
                }
            }

            // If intersects with one of these blocks, assume ghost block
            for (GhostData ghostData : unknownStuff.values()) {
                if (ghostData.getPlacedBox().isIntersected(movement)) {
                    result.exempt();
                    setbackUtil.executeForceResync("unknown");
                    break;
                }
            }
        }

        // Remove illegal blocks if they are in unloaded chunks
        unknownStuff.entrySet().removeIf(entry -> player.compensatedWorld.getChunk(entry.getKey().getX() >> 4, entry.getKey().getZ() >> 4) == null);
        pseudoPlaces.removeIf(ghost -> player.compensatedWorld.getChunk(ghost.getPosition().getX() >> 4, ghost.getPosition().getZ() >> 4) == null);
        bucketUseLocations.removeIf(location -> player.compensatedWorld.getChunk(location.getX() >> 4, location.getZ() >> 4) == null);
    }

    @Override
    public void tick() {
        // TODO: Replace with ServerTickEndEvent
        // We must wait until the next tick for the server to verify these blocks
        unknownStuff.values().forEach(GhostData::tick);
    }

    public void onEndOfTickEvent() {
        // If we have any illegal positions for two ticks, setback with that teleport
        for (Map.Entry<BlockPos, GhostData> data : unknownStuff.entrySet()) {
            GhostData ghostData = data.getValue();

            // Revert ghost blocks to server state, making sure to not fuck with block predictions
            // We don't send blocks to the client, but we will if the player flags later on
            if (!ghostData.isValid() && !ghostData.isReverted() && ghostData.ticks >= 2) {
                if (player.compensatedWorld.hasPendingBlockPrediction(data.getKey())) {
                    continue;
                }
                ghostData.revert();

            // No predictions, ensure no ghost block. Special case when bukkit fails to confirm block place.
            if (player.bukkitPlayer != null && ghostData.isReverted()) {
                ResyncWorldUtil.resyncPositions(player, new SimpleCollisionBox(data.getKey()).expand(1));
            }
        }
    }
    }

    public boolean isDesyncPos(BlockPos pos) {
        for (GhostData ghostData : unknownStuff.values()) {
            if (ghostData.isReverted() && ghostData.getPlacedPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public void handleUpdateServerBlockState(BlockPos position, BlockData newState) {
        GhostData existing = unknownStuff.get(position);
        if (existing != null) {
            existing.serverState = newState;
        }
    }

    // If there is a block update, remove the illegal block
    public void handleNewBlock(BlockPos blockPos) {
        unknownStuff.remove(blockPos);
        pseudoPlaces.remove(blockPos);
    }

    public void handlePseudoPlace(GhostBlock ghostBlock) {
        pseudoPlaces.add(ghostBlock);
    }

    // This method is called if and only if it isn't placed on a known desync pos
    public void handleBlockPlace(BlockPos placeBox, BlockData original, BlockData newState, BlockPlace place) {
        // Assume permanent desync
        if (place.isUseItem()) {
            bucketUseLocations.add(placeBox);
        }

        GhostData existing = unknownStuff.get(placeBox);
        if (existing != null) {
            original = existing.getServerState();
        }

        Material type = newState.getMaterial();
        double height = NmsBlockTags.isFence(type) || NmsBlockTags.isWall(type) || NmsBlockTags.isFenceGate(type) ? 1.5 : 1;

        GhostData ghostData = new GhostData(
                placeBox,
                new SimpleCollisionBox(
                        placeBox.getX(),
                        placeBox.getY(),
                        placeBox.getZ(),
                        placeBox.getX() + 1,
                        placeBox.getY() + height,
                        placeBox.getZ() + 1
                ),
                original,
                newState,
                place
        );
        unknownStuff.put(placeBox, ghostData);

        // Placing against own valid means the player can't extend their ghost blocks?
        if (!place.getPlacedAgainstBlockLocation().equals(place.getPlacedBlockPos())) {
            GhostData placedAgainst = unknownStuff.get(place.getPlacedAgainstBlockLocation());
            if (placedAgainst != null) {
                // If placedAgainst is reverted, then this will also be reverted
                placedAgainst.addDependent(ghostData);
            }
        }
    }

    public void onServerValidBucketUse(PlayerBucketEvent bucketEvent) {
        BlockPos blockPos = new BlockPos(bucketEvent.getBlock().getX(), bucketEvent.getBlock().getY(), bucketEvent.getBlock().getZ());

        onPlace(blockPos, bucketEvent.isCancelled());
    }

    public void onServerValidBlockPlace(BlockPlaceEvent event) {
        // Remove from unknownBlocks if exists
        if (event instanceof BlockMultiPlaceEvent) {
            for (BlockState block : ((BlockMultiPlaceEvent) event).getReplacedBlockStates()) {
                BlockPos placeBox = new BlockPos(block.getX(), block.getY(), block.getZ());
                onPlace(placeBox, event.isCancelled());
            }
        } else {
            BlockPos placeBox = new BlockPos(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
            onPlace(placeBox, event.isCancelled());
        }
    }

    private void onPlace(BlockPos pos, boolean isCancelled) {
        GhostData data = unknownStuff.get(pos);
        if (data != null) {
            if (isCancelled) {
                if (!player.compensatedWorld.hasPendingBlockPrediction(pos)) {
                    data.revert();
                }
            } else {
                data.setValid(true);
            }
        }
    }

    @Data
    @RequiredArgsConstructor
    public final class GhostData {
        final BlockPos placedPos;
        final SimpleCollisionBox placedBox;
        BlockData serverState;
        final BlockData clientState;
        final BlockPlace place;
        LinkedList<GhostData> revertIfReverted = null;

        boolean isValid;
        boolean isReverted;
        int ticks;

        public GhostData(BlockPos placedPos, SimpleCollisionBox placedBox, BlockData serverState, BlockData clientState, BlockPlace place) {
            this.placedPos = placedPos;
            this.placedBox = placedBox;
            this.serverState = serverState;
            this.clientState = clientState;
            this.place = place;
        }

        private void addDependent(GhostData ghostData) {
            if (revertIfReverted == null) revertIfReverted = new LinkedList<>();
            revertIfReverted.add(ghostData);
        }

        private void revert() {
            if (isReverted) return;
            if (player.compensatedWorld.hasPendingBlockPrediction(placedPos)) return;
            player.compensatedWorld.applyBlockChangeRawDANGER(placedPos.getX(), placedPos.getY(), placedPos.getZ(), NmsBlockTags.toNmsState(getServerState()));
            setReverted(true);
            // We must also revert all blocks that were placed on this reverted block
            if (revertIfReverted != null) {
                for (GhostData ghostData : revertIfReverted) {
                    ghostData.revert();
                }
            }
        }

        private void tick() {
            ticks++;
        }
    }
}
