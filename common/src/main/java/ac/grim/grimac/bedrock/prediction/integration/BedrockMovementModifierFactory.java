package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;

final class BedrockMovementModifierFactory {
    private BedrockMovementModifierFactory() {
    }

    static MovementModifierState create(
            BedrockPlayerContext playerContext,
            GrimPlayer player,
            SimulationContext context
    ) {
        boolean mayFly = trustedMayFlyAbility(player);
        return new MovementModifierState(
                playerContext.wearingElytra(),
                mayFly,
                player != null && mayFly && player.isFlying,
                trustedFlySpeed(player),
                false,
                playerContext.riptideLevel() > 0
                        && context != null
                        && context.getVehicle() == null,
                player != null && player.compensatedWorld != null && player.compensatedWorld.isRaining,
                false,
                0.35D,
                0L
        );
    }

    private static boolean trustedMayFlyAbility(GrimPlayer player) {
        return player != null && player.canFly;
    }

    private static double trustedFlySpeed(GrimPlayer player) {
        if (player == null || !Double.isFinite(player.flySpeed) || player.flySpeed < 0.0F) {
            return 0.05D;
        }
        return player.flySpeed;
    }
}
