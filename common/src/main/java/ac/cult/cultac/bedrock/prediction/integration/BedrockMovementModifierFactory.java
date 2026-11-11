package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;

final class BedrockMovementModifierFactory {
    private BedrockMovementModifierFactory() {}

    static MovementModifierState create(
            BedrockPlayerContext playerContext, CultPlayer player, SimulationContext context) {
        boolean mayFly = trustedMayFlyAbility(player);
        return new MovementModifierState(
                playerContext.wearingElytra(),
                mayFly,
                player != null && mayFly && player.isFlying,
                player != null && player.gamemode == GameMode.CREATIVE,
                trustedFlySpeed(player),
                false,
                playerContext.riptideLevel() > 0 && context != null && context.getVehicle() == null,
                player != null && player.compensatedWorld != null && player.compensatedWorld.isRaining,
                false,
                0.35D,
                0L,
                hasGravity(player));
    }

    // The player's no-gravity entity data reaches the client as its gravity actor flag.
    private static boolean hasGravity(CultPlayer player) {
        return player == null
                || player.compensatedEntities == null
                || player.compensatedEntities.getSelf().hasGravity;
    }

    private static boolean trustedMayFlyAbility(CultPlayer player) {
        return player != null && player.canFly;
    }

    private static double trustedFlySpeed(CultPlayer player) {
        if (player == null || !Double.isFinite(player.flySpeed) || player.flySpeed < 0.0F) {
            return 0.05D;
        }
        return player.flySpeed;
    }
}
