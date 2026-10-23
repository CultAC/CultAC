package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.interaction.MiningSpeed;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;

/** Player#getDestroySpeed's player inputs as this client last received them. */
public final class ClientMiningState {
    private ClientMiningState() {}

    /**
     * LivingEntity#detectEquipmentUpdates runs only on the server, so the client's mining attributes,
     * including enchantment modifiers such as efficiency, are exactly the synced attribute values.
     */
    public static MiningSpeed.State of(CultPlayer player) {
        var self = player.compensatedEntities.getSelf();
        return new MiningSpeed.State(
                player.packetStateData.packetPlayerOnGround,
                eyeInWater(player),
                self.miningEfficiency,
                self.blockBreakSpeed,
                self.submergedMiningSpeed,
                amplifier(player, MovementEffect.HASTE),
                amplifier(player, MovementEffect.CONDUIT_POWER),
                amplifier(player, MovementEffect.MINING_FATIGUE));
    }

    /** Geyser's BlockUtils#getPlayerDestroySpeed keeps the Mining Fatigue table that 26.3 replaced. */
    public static BlockSimClientVersion fatigueForm(
            CultPlayer player, ClientComponentRegistries.SimulationActions binding) {
        return player.isBedrockMovement()
                ? BlockSimClientVersion.V26_2
                : BlockSimClientVersion.of(binding.client().protocol());
    }

    private static int amplifier(CultPlayer player, MovementEffect effect) {
        Integer level = player.compensatedEntities.getPotionLevelForPlayer(effect);
        return level == null ? -1 : level;
    }

    private static boolean eyeInWater(CultPlayer player) {
        SimpleCollisionBox body = GetBoundingBox.getBoundingBoxFromPosAndSize(
                player.x,
                player.y,
                player.z,
                player.pose.width * player.dimensionScale,
                player.pose.height * player.dimensionScale);
        return ClientFluidQueries.eyesInWater(
                player, body, new Vec3(player.x, player.y, player.z), player.y + player.getEyeHeight());
    }
}
