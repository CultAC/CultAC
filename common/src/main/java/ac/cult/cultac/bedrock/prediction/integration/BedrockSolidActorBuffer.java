package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;

/**
 * Where a client may show the solid actors around the player, and how far they may push its move. Cult's box for an
 * actor can stand off the box the client meets: by a lerp step for a 1.26.60 or later client, anywhere its history
 * delay allows for an earlier one. A move such an actor ends along an axis may end where the actor, shown there, would
 * end it; a move it touches may be pushed away from it by no more than its depenetration limit.
 */
public final class BedrockSolidActorBuffer {
    // Where a client before 1.26.60 may show each remote actor.
    public final BedrockLegacyActorEnvelope legacy = new BedrockLegacyActorEnvelope();
    // The same for the actors only the client holds, such as Geyser's world border armor stands.
    public final BedrockLegacyActorEnvelope legacyClientOnly = new BedrockLegacyActorEnvelope();
    // Where a 1.26.60 or later client may show each remote actor in its lerp, and the actors only it holds.
    public final BedrockActorStepEnvelope steps = new BedrockActorStepEnvelope();
    public final BedrockActorStepEnvelope stepsClientOnly = new BedrockActorStepEnvelope();
    // Per axis and side (0 toward negative, 1 toward positive), the farthest an actor touching the move of client tick
    // pushTick may push it out.
    private long pushTick = Long.MIN_VALUE;
    private final float[][] push = new float[3][2];

    /**
     * A solid actor centred at ({@code actorX}, {@code actorZ}), which may stand up to {@code buffer} from
     * Cult's box for it, touches the move of client tick {@code tick} centred at ({@code moverX}, {@code moverZ}): it can
     * push the move away from itself by no more than that buffer, nor more than its depenetration limit allows, and down
     * by no more than a solid actor's limit.
     */
    public void touchedBy(
            long tick,
            float buffer,
            BedrockDepenetrationLimit limit,
            double actorX,
            double actorZ,
            double moverX,
            double moverZ) {
        if (tick != pushTick) {
            pushTick = tick;
            for (float[] axis : push) axis[0] = axis[1] = 0.0F;
        }
        float x = Math.min(buffer, limit.x()), z = Math.min(buffer, limit.z());
        int sideX = moverX >= actorX ? 1 : 0, sideZ = moverZ >= actorZ ? 1 : 0;
        push[0][sideX] = Math.max(push[0][sideX], x);
        push[2][sideZ] = Math.max(push[2][sideZ], z);
        // Down only, and by no more than a solid actor's limit: a lift onto an actor is simulated, not pushed.
        push[1][0] = Math.max(push[1][0], Math.min(buffer, BedrockDepenetrationLimit.SOLID_ACTOR.y()));
    }

    /**
     * The farthest such an actor may push the move of client tick {@code tick} along {@code axis} toward its positive
     * ({@code positive}) or negative side, or 0 for none.
     */
    public float actorPush(long tick, int axis, boolean positive) {
        return tick == pushTick ? push[axis][positive ? 1 : 0] : 0.0F;
    }

    /** Whether a solid actor that may stand off Cult's box touches the move of client tick {@code tick}. */
    public boolean actorTouched(long tick) {
        return tick == pushTick;
    }



}
