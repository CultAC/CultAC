package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.interaction.MiningSpeed;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;

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

    /** {@link #of(CultPlayer)}'s values held in reusable fields; {@link #state()} builds the record from them. */
    public static final class Inputs {
        public boolean onGround;
        public boolean eyesInWater;
        public double efficiency;
        public double blockBreakSpeed;
        public double submergedSpeed;
        public int haste;
        public int conduitPower;
        public int fatigue;

        /** Reads what {@link #of(CultPlayer)} reads, in the same order. */
        public Inputs read(CultPlayer player) {
            var self = player.compensatedEntities.getSelf();
            onGround = player.packetStateData.packetPlayerOnGround;
            eyesInWater = eyeInWater(player);
            efficiency = self.miningEfficiency;
            blockBreakSpeed = self.blockBreakSpeed;
            submergedSpeed = self.submergedMiningSpeed;
            haste = amplifier(player, MovementEffect.HASTE);
            conduitPower = amplifier(player, MovementEffect.CONDUIT_POWER);
            fatigue = amplifier(player, MovementEffect.MINING_FATIGUE);
            return this;
        }

        public MiningSpeed.State state() {
            return new MiningSpeed.State(
                    onGround, eyesInWater, efficiency, blockBreakSpeed, submergedSpeed, haste, conduitPower, fatigue);
        }
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

    // GetBoundingBox#getBoundingBoxFromPosAndSize's bounds, sorted as SimpleCollisionBox#sort orders them.
    private static boolean eyeInWater(CultPlayer player) {
        float halfWidth = player.pose.width * player.dimensionScale / 2.0F;
        float height = player.pose.height * player.dimensionScale;
        double x = player.x, y = player.y, z = player.z;
        double lowX = x - halfWidth, lowZ = z - halfWidth, highX = x + halfWidth, highY = y + height, highZ = z + halfWidth;
        return ClientFluidQueries.eyesInWater(
                player,
                Math.min(lowX, highX),
                Math.min(y, highY),
                Math.min(lowZ, highZ),
                Math.max(lowX, highX),
                Math.max(y, highY),
                Math.max(lowZ, highZ),
                x,
                z,
                y + player.getEyeHeight());
    }
}
