package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * LivingEntity#onSyncedDataUpdated runs LivingEntity#setPosToBed on a Java client that receives its sleeping position.
 * Entity#setPos moves the player without touching deltaMovement, so a sleeper still moving travels on from the bed.
 */
public final class ClientSleepingPosition {
    // Before 26.3, LivingEntity#setPosToBed lifts the sleeper this far whatever the block is.
    private static final double LEGACY_SLEEP_HEIGHT = 0.6875;
    // From 26.3, LivingEntity#setPosToBed adds this to AbstractBedBlock#getSleepHeight.
    private static final double ADJUST_HEIGHT = 0.125;
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    private ClientSleepingPosition() {}

    /** Moves a Java client's player to its bed, as the synced sleeping position does before its next travel. */
    public static void setPosToBed(CultPlayer player, BlockPos bed) {
        if (player.isBedrockMovement() || player.compensatedEntities.getSelf().inVehicle()) return;
        Vec3 position = bedPosition(player, bed);
        if (position == null) return;
        player.lastX = player.x;
        player.lastY = player.y;
        player.lastZ = player.z;
        player.x = position.x;
        player.y = position.y;
        player.z = position.z;
        player.packetStateData.clientSidePosition = position;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
        player.compensatedEntities.observeLocalPlayerPosition(position);
    }

    /**
     * Null when a 26.3+ client's LivingEntity#setPosToBed finds no AbstractBedBlock#getSleepHeight there and leaves
     * the player.
     */
    static @Nullable Vec3 bedPosition(CultPlayer player, BlockPos bed) {
        double height;
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            int state = player.compensatedWorld.getBlockStateIdAt(bed);
            if (!BlockFamilies.ABSTRACT_BED.test(state)) return null;
            BlockPos layingOn = bed;
            if (BlockIds.is(state, BlockIds.STRAW_BED) && "head".equals(REGISTRY.value(state, "part"))) {
                // StrawBedBlock#getSleepHeight lays a head's sleeper on its foot: the half its connected direction
                // reaches, which must be a straw bed foot.
                Direction facing =
                        Direction.valueOf(REGISTRY.value(state, "facing").toUpperCase(Locale.ROOT));
                layingOn = bed.relative(facing.getOpposite());
                state = player.compensatedWorld.getBlockStateIdAt(layingOn);
                if (!BlockIds.is(state, BlockIds.STRAW_BED) || !"foot".equals(REGISTRY.value(state, "part"))) {
                    return null;
                }
            }
            // AbstractBedBlock#getSleepHeight is the top of BlockState#getShape, which a bed also collides with.
            double[] top = {Double.NEGATIVE_INFINITY};
            NativeBlockCollisionHelper.getContextFreeCollisionShape(
                            player, state, layingOn.getX(), layingOn.getY(), layingOn.getZ())
                    .forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> top[0] = Math.max(top[0], maxY));
            if (top[0] == Double.NEGATIVE_INFINITY) return null;
            height = top[0] + ADJUST_HEIGHT;
        } else {
            height = LEGACY_SLEEP_HEIGHT;
        }
        return new Vec3(bed.getX() + 0.5, bed.getY() + height, bed.getZ() + 0.5);
    }
}
