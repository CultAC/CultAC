package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.blockplace.GhostBlock;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

// Agreement with bukkit is more important than agreement with the client (for buckets)
//
// Additionally, we don't give a shit if we setback too much here accidentally
// We already would need to setback. lol. so it doesn't matter.
//
// A block-change acknowledgement ends every client prediction up to its sequence
// (ClientLevel#handleBlockChangedAck), whether or not block updates precede it.
public class GhostBlockMitigator extends CultProcessor implements PostPredictionListener {

    // The server answers an in-range click within a round trip; only dropped or out-of-range clicks wait for the cap.
    private static final int MAX_PSEUDO_PLACES = 256;

    public GhostBlockMitigator(CultPlayer player) {
        super(player);
    }

    // If a player's movement requires colliding with one of these blocks to be valid, mark it as a suspicious block
    //
    // Keep this data until the server has confirmed these places - where we can then remove the data
    public final Map<BlockPos, GhostData> unknownStuff = new ConcurrentHashMap<>();

    // If a player uses a bucket in flags in the area of the bucket
    // Setback the player without flagging as cheating, remove this bucket place, then resend slightly around player
    // Kept until the acknowledgement for the use reaches the client, or its chunk unloads
    // Keyed by position to cap the limit of this list, to stop crashes
    private final Map<BlockPos, Integer> bucketUseLocations = new HashMap<>();
    private final Map<BlockPos, GhostBlock> pseudoPlaces = new LinkedHashMap<>();

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (predictionComplete.isTeleport()) return;

        PredictionResult result = predictionComplete.getPredictionResult();

        if (result.getFlagSeverity() > 0
                && (!unknownStuff.isEmpty() || !bucketUseLocations.isEmpty() || !pseudoPlaces.isEmpty())) {
            // Check if intersecting with one of the player's ghost blocks
            Vec3 from = result.getSimulationContext().getStart();
            Vec3 to = result.getSimulationContext().getEnd();

            // TODO: Support vehicle hitbox size...
            SimpleCollisionBox movement = new SimpleCollisionBox(from, to);
            movement.expandMax(0.33, 1.83, 0.33);
            movement.expandMin(-0.33, -0.03, -0.33);

            final SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
            for (BlockPos fuckedLoc : bucketUseLocations.keySet()) {
                SimpleCollisionBox bucketBox = new SimpleCollisionBox(fuckedLoc);
                if (movement.isIntersected(bucketBox)) {
                    result.exempt();
                    setbackUtil.executeForceResync("bucket");
                    break;
                }
            }

            for (GhostBlock ghostBlock : pseudoPlaces.values()) {
                // ignore end crystals
                if (isEndCrystal(ghostBlock)) continue;

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
        unknownStuff
                .entrySet()
                .removeIf(entry -> player.compensatedWorld.getChunk(
                                entry.getKey().getX() >> 4, entry.getKey().getZ() >> 4)
                        == null);
        pseudoPlaces
                .values()
                .removeIf(ghost -> player.compensatedWorld.getChunk(
                                ghost.getPosition().getX() >> 4,
                                ghost.getPosition().getZ() >> 4)
                        == null);
        bucketUseLocations
                .keySet()
                .removeIf(location ->
                        player.compensatedWorld.getChunk(location.getX() >> 4, location.getZ() >> 4) == null);
    }

    /** Forgets every placement; call when the client replaces its level. */
    public void clearLevel() {
        unknownStuff.clear();
        bucketUseLocations.clear();
        pseudoPlaces.clear();
    }

    public boolean isDesyncPos(BlockPos pos) {
        for (GhostData ghostData : unknownStuff.values()) {
            if (ghostData.isReverted() && ghostData.getPlacedPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public void handleUpdateServerBlockState(BlockPos position, int newState) {
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
        GhostBlock existing = pseudoPlaces.remove(ghostBlock.getPosition());
        if (existing != null && isEndCrystal(ghostBlock) && !isEndCrystal(existing)) ghostBlock = existing;
        ghostBlock.setSequence(player.compensatedWorld.clientPredictionSequence());
        if (pseudoPlaces.size() >= MAX_PSEUDO_PLACES) {
            pseudoPlaces.remove(pseudoPlaces.keySet().iterator().next());
        }
        pseudoPlaces.put(ghostBlock.getPosition(), ghostBlock);
    }

    private static boolean isEndCrystal(GhostBlock ghostBlock) {
        return ghostBlock.getItemUsed() != null && ghostBlock.getItemUsed().getItem() == ItemTypes.END_CRYSTAL;
    }

    // This method is called if and only if it isn't placed on a known desync pos
    public void handleBlockPlace(BlockPos placeBox, int original, int newState, BlockPlace place) {
        int sequence = player.compensatedWorld.clientPredictionSequence();
        if (place.isUseItem()) {
            bucketUseLocations.put(placeBox, sequence);
        }

        GhostData existing = unknownStuff.get(placeBox);
        if (existing != null) {
            original = existing.getServerState();
        }

        double height = ClientBlockProperties.isFence(newState)
                        || ClientBlockProperties.isWall(newState)
                        || ClientBlockProperties.isFenceGate(newState)
                ? 1.5
                : 1;

        GhostData ghostData = new GhostData(
                placeBox,
                new SimpleCollisionBox(
                        placeBox.getX(),
                        placeBox.getY(),
                        placeBox.getZ(),
                        placeBox.getX() + 1,
                        placeBox.getY() + height,
                        placeBox.getZ() + 1),
                original,
                newState,
                place,
                sequence);
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

    /**
     * Called when the server's block-change acknowledgement is sent. Every block update the server
     * produced for the acknowledged placements precedes it, so the newest sent state is the server's.
     */
    public void onServerAckSent(int sequence) {
        for (GhostData data : unknownStuff.values()) {
            if (data.place.sequence > sequence || data.isValid()) continue;
            int serverState = player.compensatedWorld.getServerSentBlockStateIdAt(data.getPlacedPos());
            if (serverState == CompensatedWorld.UNKNOWN_SERVER_STATE) continue;
            if (serverState == data.getClientState()) {
                data.setValid(true);
            } else {
                data.serverState = serverState;
                if (!player.compensatedWorld.hasPendingBlockPrediction(data.getPlacedPos())) data.revert();
            }
        }
    }

    /**
     * Called once the client has applied a block-change acknowledgement. Positions whose newest action is at or
     * below the sequence hold the server's state again, unless a later prediction still owns them
     * (BlockStatePredictionHandler#endPredictionsUpTo).
     */
    public void onClientAck(int sequence) {
        CompensatedWorld world = player.compensatedWorld;
        unknownStuff
                .values()
                .removeIf(data -> data.sequence <= sequence && !world.hasPendingBlockPrediction(data.placedPos));
        pseudoPlaces
                .values()
                .removeIf(ghost ->
                        ghost.getSequence() <= sequence && !world.hasPendingBlockPrediction(ghost.getPosition()));
        bucketUseLocations
                .entrySet()
                .removeIf(entry -> entry.getValue() <= sequence && !world.hasPendingBlockPrediction(entry.getKey()));
    }

    @Data
    public final class GhostData {
        final BlockPos placedPos;
        final SimpleCollisionBox placedBox;
        int serverState;
        final int clientState;
        final BlockPlace place;
        final int sequence;
        LinkedList<GhostData> revertIfReverted = null;

        boolean isValid;
        boolean isReverted;

        public GhostData(
                BlockPos placedPos,
                SimpleCollisionBox placedBox,
                int serverState,
                int clientState,
                BlockPlace place,
                int sequence) {
            this.placedPos = placedPos;
            this.placedBox = placedBox;
            this.serverState = serverState;
            this.clientState = clientState;
            this.place = place;
            this.sequence = sequence;
        }

        private void addDependent(GhostData ghostData) {
            if (revertIfReverted == null) revertIfReverted = new LinkedList<>();
            revertIfReverted.add(ghostData);
        }

        private void revert() {
            if (isReverted) return;
            if (player.compensatedWorld.hasPendingBlockPrediction(placedPos)) return;
            player.compensatedWorld.applyBlockChangeRawDANGER(
                    placedPos.getX(), placedPos.getY(), placedPos.getZ(), getServerState());
            setReverted(true);
            // We must also revert all blocks that were placed on this reverted block
            if (revertIfReverted != null) {
                for (GhostData ghostData : revertIfReverted) {
                    ghostData.revert();
                }
            }
        }
    }
}
