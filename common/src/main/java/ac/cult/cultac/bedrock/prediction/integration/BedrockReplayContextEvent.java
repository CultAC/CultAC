package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import java.util.Map;

/** Patches only the fields supplied by an update, retaining all other historical inputs. */
public record BedrockReplayContextEvent(Map<String, Float> attributes, Integer effectId, Integer effectLevel,
        int duration, Integer gameType, Boolean usingItem) implements BedrockReplayEvent {
    public BedrockReplayContextEvent { attributes = Map.copyOf(attributes); }

    @Override public BedrockSimulation.Input input(BedrockSimulation.Input input, long elapsed) {
        return apply(input, elapsed, null);
    }

    @Override public BedrockSimulation.Input restoreInput(BedrockSimulation.Input input, BedrockSimulation.Input recorded) {
        return apply(input, 0, recorded.snapshot().movementContext());
    }

    private BedrockSimulation.Input apply(BedrockSimulation.Input input, long elapsed, BedrockMovementContext recorded) {
        var c = input.snapshot().movementContext();
        var a = c.attributeState();
        double speed = attributes.containsKey("minecraft:movement") ? attributes.get("minecraft:movement") : a.horizontalInputBaseMovementSpeed();
        a = new AttributeState(attributes.containsKey("minecraft:movement") ? speed : a.baseMovementSpeed(), speed,
                attributes.getOrDefault("minecraft:underwater_movement", a.underwaterMovementSpeed()),
                attributes.getOrDefault("minecraft:lava_movement", a.lavaMovementSpeed()),
                attributes.getOrDefault("minecraft:horse.jump_strength", a.jumpStrength()), a.frictionModifier());
        if (recorded != null) {
            var original = recorded.attributeState();
            a = new AttributeState(attributes.containsKey("minecraft:movement") ? original.baseMovementSpeed() : a.baseMovementSpeed(),
                    attributes.containsKey("minecraft:movement") ? original.horizontalInputBaseMovementSpeed() : a.horizontalInputBaseMovementSpeed(),
                    attributes.containsKey("minecraft:underwater_movement") ? original.underwaterMovementSpeed() : a.underwaterMovementSpeed(),
                    attributes.containsKey("minecraft:lava_movement") ? original.lavaMovementSpeed() : a.lavaMovementSpeed(),
                    attributes.containsKey("minecraft:horse.jump_strength") ? original.jumpStrength() : a.jumpStrength(), a.frictionModifier());
        }
        var e = c.effectState();
        if (effectId != null) {
            int level = duration >= 0 && elapsed >= duration ? 0 : effectLevel;
            if (recorded != null) {
                var original = recorded.effectState();
                level = switch (effectId) {
                    case 1 -> original.speedLevel();
                    case 2 -> original.slownessLevel();
                    case 8 -> original.jumpBoostLevel();
                    case 15 -> original.blindness() ? 1 : 0;
                    case 24 -> original.levitationLevel();
                    case 27 -> original.slowFalling() ? 1 : 0;
                    case 33 -> original.weaving() ? 1 : 0;
                    default -> level;
                };
            }
            e = switch (effectId) {
                case 1 -> e.withSpeedLevel(level);
                case 2 -> e.withSlownessLevel(level);
                case 8 -> e.withJumpBoostLevel(level);
                case 15 -> new BedrockEffectState(e.slownessLevel(), e.speedLevel(), e.jumpBoostLevel(),
                        e.levitationLevel(), level > 0, e.slowFalling(), e.weaving(), e.movementSpeedEffectOrder());
                case 24 -> e.withLevitationLevel(level);
                case 27 -> e.withSlowFalling(level > 0);
                case 33 -> e.withWeaving(level > 0);
                default -> e;
            };
        }
        var m = c.modifierState();
        if (gameType != null || usingItem != null) {
            boolean mayFly = gameType == null ? m.movementAbilityMayFly() : gameType == 1 || gameType == 3 || gameType == 4 || gameType == 6;
            m = new MovementModifierState(m.elytraGlideAvailable(), mayFly, mayFly && m.movementAbilityFlying(),
                    gameType == null ? m.movementAbilityInstabuild() : gameType == 1,
                    m.movementAbilityFlySpeed(), m.navigationCanWalkInLava(), m.riptideAvailable(), m.rainContact(),
                    usingItem == null ? m.itemUseSlowdownActive() : usingItem,
                    m.itemUseMovementModifier(), m.itemUseSlowdownDurationTicks());
        }
        if (recorded != null && (gameType != null || usingItem != null)) {
            var original = recorded.modifierState();
            m = new MovementModifierState(m.elytraGlideAvailable(),
                    gameType == null ? m.movementAbilityMayFly() : original.movementAbilityMayFly(),
                    gameType == null ? m.movementAbilityFlying() : original.movementAbilityFlying(),
                    gameType == null ? m.movementAbilityInstabuild() : original.movementAbilityInstabuild(),
                    m.movementAbilityFlySpeed(), m.navigationCanWalkInLava(), m.riptideAvailable(), m.rainContact(),
                    usingItem == null ? m.itemUseSlowdownActive() : original.itemUseSlowdownActive(),
                    m.itemUseMovementModifier(), m.itemUseSlowdownDurationTicks());
        }
        var context = new BedrockMovementContext(e, a, c.worldState(), c.equipmentState(),
                c.entityContactState(), m, c.playerDimensionsState());
        return new BedrockSimulation.Input(input.previousState(), input.frame(), input.intent(),
                input.snapshot().withMovementContext(context), input.canStep(), input.maxUpStep(),
                input.mobJumpComponent(), input.actorMovementTick(), input.acceptedTeleport(), input.control(), input.glideBoost());
    }
}
