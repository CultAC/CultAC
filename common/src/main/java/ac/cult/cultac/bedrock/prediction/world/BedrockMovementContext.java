package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import java.util.Objects;

public record BedrockMovementContext(
    BedrockEffectState effectState,
    AttributeState attributeState,
    WorldContactState worldState,
    EquipmentState equipmentState,
    EntityContactState entityContactState,
    MovementModifierState modifierState,
    PlayerDimensionsState playerDimensionsState
) {
    public BedrockMovementContext {
        effectState = Objects.requireNonNull(effectState, "effectState");
        attributeState = Objects.requireNonNull(attributeState, "attributeState");
        worldState = Objects.requireNonNull(worldState, "worldState");
        equipmentState = Objects.requireNonNull(equipmentState, "equipmentState");
        entityContactState = Objects.requireNonNull(entityContactState, "entityContactState");
        modifierState = Objects.requireNonNull(modifierState, "modifierState");
        playerDimensionsState = Objects.requireNonNull(playerDimensionsState, "playerDimensionsState");
    }

    public boolean inWater() {
        return worldState.waterContact();
    }

    public boolean inLava() {
        return worldState.lavaContact();
    }

    public boolean inAir() {
        return !worldState.waterContact() && !worldState.lavaContact();
    }

    public Medium liquidMovementMedium() {
        return worldState.liquidMovementMedium();
    }

    public boolean waterCurrentPositiveX() {
        return worldState.fluidState().currentPositiveX();
    }

    public boolean waterCurrentNegativeX() {
        return worldState.fluidState().currentNegativeX();
    }

    public boolean waterCurrentPositiveZ() {
        return worldState.fluidState().currentPositiveZ();
    }

    public boolean waterCurrentNegativeZ() {
        return worldState.fluidState().currentNegativeZ();
    }

    public boolean inUpwardBubbleColumn() {
        return worldState.fluidState().bubbleColumnUp();
    }

    public boolean inDownwardBubbleColumn() {
        return worldState.fluidState().bubbleColumnDown();
    }

    public boolean actorSwimming() {
        return worldState.fluidState().actorSwimming();
    }

    public boolean dolphinBoostAvailable() {
        return entityContactState.dolphinBoostAvailable();
    }

    public boolean elytraGlideAvailable() {
        return modifierState.elytraGlideAvailable();
    }

    public boolean movementAbilityMayFly() {
        return modifierState.movementAbilityMayFly();
    }

    public boolean movementAbilityFlying() {
        return modifierState.movementAbilityFlying();
    }

    public double movementAbilityFlySpeed() {
        return modifierState.movementAbilityFlySpeed();
    }

    public boolean navigationCanWalkInLava() {
        return modifierState.navigationCanWalkInLava();
    }

    public boolean riptideAvailable() {
        return modifierState.riptideAvailable();
    }

    public boolean rainContact() {
        return modifierState.rainContact();
    }

    public boolean itemUseSlowdownActive() {
        return modifierState.itemUseSlowdownActive();
    }

    public double itemUseMovementModifier() {
        return modifierState.itemUseMovementModifier();
    }

    public long itemUseSlowdownDurationTicks() {
        return modifierState.itemUseSlowdownDurationTicks();
    }

    public BedrockMovementContext withPlayerDimensions(PlayerDimensionsState dimensions) {
        return new BedrockMovementContext(
            effectState,
            attributeState,
            worldState,
            equipmentState,
            entityContactState,
            modifierState,
            dimensions
        );
    }

    public BedrockMovementContext withBlockCollisionWorld(BlockCollisionWorld world) {
        return new BedrockMovementContext(
            effectState,
            attributeState,
            worldState.withBlockCollisionWorld(world),
            equipmentState,
            entityContactState,
            modifierState,
            playerDimensionsState
        );
    }

}
