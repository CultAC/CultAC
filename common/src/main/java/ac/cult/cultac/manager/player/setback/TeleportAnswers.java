package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The client answered a teleport. A Bedrock transport answer places the player, and the answer to the latest
 * correction completes it and ends the offset block.
 */
public final class TeleportAnswers {
    private final CultPlayer player;
    private final MovementGate gate;
    private final SafePositionTracker safePositions;
    private final CorrectionState corrections;

    public TeleportAnswers(
            CultPlayer player, MovementGate gate, SafePositionTracker safePositions, CorrectionState corrections) {
        this.player = player;
        this.gate = gate;
        this.safePositions = safePositions;
        this.corrections = corrections;
    }

    public TeleportAcceptData complete(TeleportData teleport, Vec3 location, boolean matchedPosition) {
        TeleportAcceptData accepted = new TeleportAcceptData();
        boolean initialSpawn = !gate.hasFullyLoaded();
        accepted.setMatchedTeleportPosition(matchedPosition);
        if (player.isBedrockMovement()
                && teleport.getBedrockTransportRevision() >= 0
                && teleport.getBedrockProvenance() != BedrockTeleportProvenance.GFP_REBASE) {
            accepted.setInitialSpawnTeleport(initialSpawn);
            gate.setLoaded(true);
            safePositions.offer(
                    new SetbackPosWithVector(location, Vec3.ZERO)
                            .provenBy(SetbackEntity.PLAYER, SafeProof.answer(teleport.getTransaction())),
                    SafePositionTracker.Write.BEDROCK_ANSWER);
        }

        SetBackData required = corrections.required();
        if (teleport.mayCompleteCultSetback()
                && required != null
                && required.getTeleportData().getTransaction() == teleport.getTransaction()) {
            corrections.complete(required);
            accepted.setSetback(required);
            accepted.setInitialSpawnTeleport(initialSpawn);
            gate.setLoaded(true);
        }
        if (!player.isBedrockMovement() && !teleport.isPositionOnly() && teleport.getBedrockTransportRevision() < 0L) {
            player.compensatedWorld.onClientTeleport();
        }
        accepted.setTeleportData(teleport.copyWithLocation(location));
        accepted.setTeleport(true);
        return accepted;
    }
}
