package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientPoseState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

record BedrockPlayerContext(
        BedrockClientPoseState pose,
        boolean actorGliding,
        PlayerDimensionsState dimensions,
        AttributeState attributes,
        EquipmentState equipment,
        BedrockEffectState effects
) {
    private static final double BEDROCK_PLAYER_WIDTH = Double.parseDouble(Float.toString(0.6F));
    private static final double BEDROCK_STANDING_HEIGHT = Double.parseDouble(Float.toString(1.8F));
    private static final double BEDROCK_SNEAKING_HEIGHT = Double.parseDouble(Float.toString(1.49F));
    private static final double BEDROCK_LOW_POSE_HEIGHT = Double.parseDouble(Float.toString(0.6F));
    private static final int BEDROCK_ELYTRA_MAX_DAMAGE = 432;

    static BedrockPlayerContext from(
            CultPlayer player,
            SimulationContext context,
            BedrockAuthInputFrame frame,
            boolean actorGliding
    ) {
        BedrockClientPoseState pose = player.bedrockState == null
                ? BedrockClientPoseState.STANDING
                : player.bedrockState.getClientPoseState(frame);
        EquipmentState equipment = equipment(player, context);
        return new BedrockPlayerContext(
                pose,
                actorGliding,
                dimensions(pose, actorGliding, context),
                attributes(player),
                equipment,
                effects(player, context)
        );
    }

    BedrockMovementContext applyTo(
            BedrockMovementContext movementContext,
            CultPlayer player,
            SimulationContext context
    ) {
        return new BedrockMovementContext(
                effects,
                attributes,
                movementContext.worldState(),
                equipment,
                movementContext.entityContactState(),
                BedrockMovementModifierFactory.create(this, player, context),
                dimensions);
    }

    int riptideLevel() {
        return equipment.riptideLevel();
    }

    boolean wearingLeatherBoots() {
        return equipment.leatherBoots();
    }

    boolean wearingElytra() {
        return equipment.elytraEquipped();
    }

    private static PlayerDimensionsState dimensions(
            BedrockClientPoseState pose,
            boolean actorGliding,
            SimulationContext context
    ) {

        double height = pose.lowHeightPose() || actorGliding
                ? BEDROCK_LOW_POSE_HEIGHT
                : pose.sneaking() ? BEDROCK_SNEAKING_HEIGHT : BEDROCK_STANDING_HEIGHT;
        return new PlayerDimensionsState(BEDROCK_PLAYER_WIDTH, height * context.getScale());
    }

    static AttributeState attributes(CultPlayer player) {
        CompensatedEntities entities = player.compensatedEntities;
        double movementSpeed = entities == null
                ? AttributeState.DEFAULT_BASE_MOVEMENT_SPEED
                : entities.getPlayerMovementSpeed();
        double horizontalInputBaseMovementSpeed = entities == null
                ? AttributeState.DEFAULT_BASE_MOVEMENT_SPEED
                : entities.getBedrockPlayerMovementSpeed();
        float underwaterMovementSpeed = AttributeState.DEFAULT_UNDERWATER_MOVEMENT_SPEED;
        float lavaMovementSpeed = AttributeState.DEFAULT_LAVA_MOVEMENT_SPEED;
        float jumpStrength = entities == null
                ? AttributeState.DEFAULT_JUMP_STRENGTH
                : entities.getBedrockPlayerJumpStrength();
        return new AttributeState(
                movementSpeed,
                horizontalInputBaseMovementSpeed,
                underwaterMovementSpeed,
                lavaMovementSpeed,
                jumpStrength,
                AttributeState.DEFAULT_FRICTION_MODIFIER);
    }

    private static EquipmentState equipment(CultPlayer player, SimulationContext context) {
        return new EquipmentState(
                Math.max(0, Math.round(context.getDepthStriderLevel())),
                0,
                Math.max(0, context.getSwiftSneakLevel()),
                riptideLevel(player),
                wearingLeatherBoots(player),
                wearingElytra(player)
        );
    }

    private static int riptideLevel(CultPlayer player) {
        if (player == null || player.getInventory() == null) {
            return 0;
        }
        return riptideLevel(player.getInventory().getClientSelectedHeldItem());
    }

    private static boolean wearingLeatherBoots(CultPlayer player) {
        if (player == null || player.getInventory() == null) {
            return false;
        }
        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
    }

    private static boolean wearingElytra(CultPlayer player) {
        if (player == null || player.getInventory() == null) {
            return false;
        }
        ItemStack chestplate = player.getInventory().getChestplate();
        return chestplate != null && chestplate.getType() == Material.ELYTRA
                && ItemUtil.getDamageValue(chestplate) < BEDROCK_ELYTRA_MAX_DAMAGE - 1;
    }

    private static BedrockEffectState effects(CultPlayer player, SimulationContext context) {
        CompensatedEntities entities = player == null ? null : player.compensatedEntities;
        int jumpBoostLevel = context.getJumpAmplifier() == null
                ? effectLevel(player, entities, "JUMP_BOOST", "JUMP")
                : Math.max(0, context.getJumpAmplifier() + 1);
        return BedrockEffectState.NONE
                .withSpeedLevel(effectLevel(player, entities, "SPEED"))
                .withSlownessLevel(effectLevel(player, entities, "SLOWNESS", "SLOW"))
                .withJumpBoostLevel(jumpBoostLevel)
                .withLevitationLevel(effectLevel(player, entities, "LEVITATION"))
                .withSlowFalling(effectLevel(player, entities, "SLOW_FALLING") > 0)
                .withWeaving(effectLevel(player, entities, "WEAVING") > 0);
    }

    @SuppressWarnings("deprecation")
    private static int effectLevel(CultPlayer player, CompensatedEntities entities, String... names) {
        if (names == null) {
            return 0;
        }
        for (String name : names) {
            PotionEffectType type = potionEffectType(name);
            if (type == null) {
                continue;
            }
            Integer amplifier = entities == null ? null : entities.getPotionLevelForPlayer(type);
            if (amplifier == null && player != null && player.bukkitPlayer != null) {
                org.bukkit.potion.PotionEffect liveEffect = player.bukkitPlayer.getPotionEffect(type);
                amplifier = liveEffect == null ? null : liveEffect.getAmplifier();
            }
            if (amplifier != null) {
                return Math.max(0, amplifier + 1);
            }
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType potionEffectType(String name) {
        return switch (name) {
            case "JUMP", "JUMP_BOOST" -> PotionEffectType.JUMP_BOOST;
            case "LEVITATION" -> PotionEffectType.LEVITATION;
            case "SLOW_FALLING" -> PotionEffectType.SLOW_FALLING;
            case "SLOW", "SLOWNESS" -> PotionEffectType.SLOWNESS;
            case "SPEED" -> PotionEffectType.SPEED;
            case "WEAVING" -> PotionEffectType.WEAVING;
            default -> PotionEffectType.getByName(name);
        };
    }

    private static int riptideLevel(ItemStack item) {
        return Math.max(0, RiptideUtil.getRiptideLevel(item));
    }
}
