package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.inventory.ItemUtil;

public class BlockBreakSpeed {

    public static double getBlockDamage(CultPlayer player, BlockPos position) {
        return getBlockDamage(player, position, false);
    }

    public static double getBlockDamage(CultPlayer player, BlockPos position, boolean debug) {
        return getBlockDamage(player, position, player.compensatedWorld.getBlockStateIdAt(position), debug);
    }

    public static double getBlockDamage(CultPlayer player, BlockPos position, int block, boolean debug) {
        SimItemStack tool = player.getInventory().getHeldItem();
        // Default destroy speed includes -1 for unbreakable blocks.
        float blockHardness = ClientBlockProperties.defaultDestroyTime(block);
        return getBlockDamage(player, tool, block, blockHardness, debug);
    }

    public static boolean couldInstantlyBreakBlock(CultPlayer player, int block) {
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack stack = player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .getItem(ac.cult.cultac.utils.inventory.Inventory.HOTBAR_OFFSET + slot);
            if (getBlockDamage(player, stack, block) >= 1.0D) return true;
        }
        return false;
    }

    public static double getBlockDamage(CultPlayer player, SimItemStack tool, int block) {
        return getBlockDamage(player, tool, block, ClientBlockProperties.defaultDestroyTime(block), false);
    }

    private static double getBlockDamage(
            CultPlayer player, SimItemStack tool, int block, float blockHardness, boolean debug) {

        if (player.gamemode == GameMode.CREATIVE) {
            if (tool != null && ItemUtil.isSword(tool.getItem())) {
                return 0;
            }
            return 1;
        }

        if (blockHardness == -1) return 0; // Unbreakable block

        ModernToolData toolData =
                modernToolData(player, ac.cult.blocksim.data.ItemComponents.tool(tool.components()), block);
        float speedMultiplier = toolData.speed;
        boolean isCorrectToolForDrop = toolData.correctForDrops;

        if (speedMultiplier > 1.0f) {
            int digSpeed = ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(tool, "minecraft:efficiency");
            if (digSpeed > 0) {
                speedMultiplier += digSpeed * digSpeed + 1;
            }
        }

        Integer digSpeed = player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.HASTE);
        Integer conduit = player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.CONDUIT_POWER);

        if (digSpeed != null || conduit != null) {
            int hasteLevel = Math.max(digSpeed == null ? 0 : digSpeed, conduit == null ? 0 : conduit);
            speedMultiplier *= 1 + (0.2 * (hasteLevel + 1));
        }

        Integer miningFatigue = player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.MINING_FATIGUE);

        if (miningFatigue != null
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            // RC1 Player#getDestroySpeed: cast the factor before multiplying.
            speedMultiplier *= miningFatigueMultiplier(miningFatigue);
        } else if (miningFatigue != null) {
            switch (miningFatigue) {
                case 0:
                    speedMultiplier *= 0.3;
                    break;
                case 1:
                    speedMultiplier *= 0.09;
                    break;
                case 2:
                    speedMultiplier *= 0.0027;
                    break;
                default:
                    speedMultiplier *= 0.00081;
            }
        }

        double eyeHeight = player.getEyeHeight();
        double eye = player.y + eyeHeight - 0.1111111119389534D;
        double d1 = (float) Math.floor(eye) + player.compensatedWorld.getWaterFluidLevelAt(player.x, eye, player.z);
        boolean eyeInWater = eye < d1;

        if (eyeInWater) {
            SimItemStack helmet = player.getInventory().getHelmet();
            SimItemStack chestplate = player.getInventory().getChestplate();
            SimItemStack leggings = player.getInventory().getLeggings();
            SimItemStack boots = player.getInventory().getBoots();

            if ((helmet == null
                            || ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(
                                            helmet, "minecraft:aqua_affinity")
                                    == 0)
                    && (chestplate == null
                            || ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(
                                            chestplate, "minecraft:aqua_affinity")
                                    == 0)
                    && (leggings == null
                            || ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(
                                            leggings, "minecraft:aqua_affinity")
                                    == 0)
                    && (boots == null
                            || ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(
                                            boots, "minecraft:aqua_affinity")
                                    == 0)) {
                speedMultiplier /= 5;
            }
        }

        if (!canUseGroundedBreakSpeed(player)) {
            speedMultiplier /= 5;
        }

        float damage = speedMultiplier / blockHardness;

        boolean canHarvest =
                !DataTables.defaults().registry().facts(block).has(StateFacts.CORRECT_TOOL) || isCorrectToolForDrop;
        if (canHarvest) {
            damage /= 30;
        } else {
            damage /= 100;
        }

        // if (debug) player.sendMessage("correctTool=" + isCorrectToolForDrop + " canHarvest=" + canHarvest);

        return damage;
    }

    static float miningFatigueMultiplier(int amplifier) {
        return (float) Math.pow(0.3, amplifier + 1);
    }

    private static ModernToolData modernToolData(
            CultPlayer player, ac.cult.blocksim.data.ItemComponents.Tool tool, int state) {
        if (tool == null) {
            return new ModernToolData(1.0F, false);
        }

        float speed = tool.defaultMiningSpeed();
        boolean correctForDrops = false;
        boolean foundSpeed = false;
        boolean foundCorrectForDrops = false;
        var clientTags = player.registryState == null ? null : player.registryState.geometryTags();
        String block = DataTables.defaults().registry().block(state).key();

        // Vanilla resolves speed and correct-for-drops independently using the first
        // matching rule which defines that property. Match named rule sets against the
        // per-player tag payload rather than this server's registry bindings.
        for (var rule : tool.rules()) {
            String named = rule.blocks().tag();
            boolean matches = named != null && clientTags != null
                    ? clientTags
                            .blocks()
                            .getOrDefault(named, java.util.List.of())
                            .contains(block)
                    : named != null
                            ? DataTables.defaults()
                                    .tags()
                                    .getOrDefault("block:" + named, java.util.Set.of())
                                    .contains(block)
                            : rule.blocks().entries().contains(block);
            if (!matches) {
                continue;
            }

            if (!foundSpeed && rule.speed() != null) {
                speed = rule.speed();
                foundSpeed = true;
            }
            if (!foundCorrectForDrops && rule.correctForDrops() != null) {
                correctForDrops = rule.correctForDrops();
                foundCorrectForDrops = true;
            }
            if (foundSpeed && foundCorrectForDrops) {
                break;
            }
        }

        return new ModernToolData(speed, correctForDrops);
    }

    private static boolean canUseGroundedBreakSpeed(CultPlayer player) {
        if (player.packetStateData.packetPlayerOnGround) {
            return true;
        }

        // Vanilla uses Player#onGround for the mining penalty. If the latest
        // movement packet has not refreshed it, only use the grounded branch
        // when the compensated world proves the current pose is supported.
        return player.boundingBox != null
                && Collisions.collide(player, 0.0D, -SimpleCollisionBox.COLLISION_EPSILON, 0.0D).y == 0.0D;
    }

    private record ModernToolData(float speed, boolean correctForDrops) {}
}
