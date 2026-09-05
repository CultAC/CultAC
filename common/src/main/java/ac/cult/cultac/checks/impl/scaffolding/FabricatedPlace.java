package ac.cult.cultac.checks.impl.scaffolding;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;

@CheckData(name = "FabricatedPlace", stableKey = "cult.scaffolding.fabricated_place", description = "Sent out of bounds cursor position")
public class FabricatedPlace extends BlockPlaceCheck {
    private static final Verbose V = Verbose.of("cursor={cursor} limit={f64:%.16f}");

    /**
     * MAX_DOUBLE_ERROR:
     * Represents the maximum possible floating-point arithmetic error that can occur
     * when calculating vectors at the Minecraft World Border (30,000,000 blocks).
     *
     * Math.ulp(30,000,000.0) is approx 3.72E-9.
     * We multiply by 2.0 to account for subtraction compounding logic in raytracing.
     *
     * This constant is safe to use everywhere; 7.450580596923828E-9 is physically indistinguishable from zero.
     */
    private static final double MAX_DOUBLE_ERROR = Math.ulp(30_000_000.0) * 2.0;

    /**
     * FLOAT_STEP_AT_ONE:
     * Represents the resolution of a Float at the value 1.0 (and 1.5).
     * Math.ulp(1.0f) is approx 1.1920929E-7.
     *
     * When checking the upper bound (1.0), we must allow this variance because a Double
     * slightly larger than 1.0 might get cast/snapped to the next available Float step.
     */
    private static final double FLOAT_STEP_AT_ONE = Math.ulp(1.0f);

    public FabricatedPlace(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        Vec3 cursor = place.getCursor();
        if (cursor == null) return;

        // Determine if we allow up to 1.5 (Lecterns, Scaffolding, etc)
        boolean isExtended = NmsBlockTags.isShapeExceedsCube(place.getPlacedAgainstMaterial())
                || place.getPlacedAgainstMaterial() == Material.LECTERN;

        double maxBound = isExtended ? 1.5 : 1.0;
        double minBound = 1.0 - maxBound; // Usually 0.0

        // ====================================================================================
        // LOWER BOUND CHECK (< 0.0)
        // ====================================================================================
        // Why only MAX_DOUBLE_ERROR?
        // Near 0.0, 'float' has extremely high resolution (down to E-45).
        // It acts like a double. When the client has a tiny calculation error (e.g. -4.44E-16),
        // the float cast preserves it exactly. We only need to account for the arithmetic noise.
        if (cursor.x < minBound - MAX_DOUBLE_ERROR ||
                cursor.y < minBound - MAX_DOUBLE_ERROR ||
                cursor.z < minBound - MAX_DOUBLE_ERROR) {

            // Alert logic
            double limit = minBound - MAX_DOUBLE_ERROR;
            var buf = V.write(verbose()).cursor((float) cursor.x, (float) cursor.y, (float) cursor.z).f64(limit);
            if (flag(buf) && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
            return;
        }

        // ====================================================================================
        // UPPER BOUND CHECK (> 1.0 or > 1.5)
        // ====================================================================================
        // Near 1.0, the "Float Step" is E-7. This is 16x larger than the Double error.
        // If the calculation is slightly off, it might snap to the NEXT float.
        // We tolerate exactly one "Float Step" of overflow.
        double upperTolerance = FLOAT_STEP_AT_ONE;

        // Note: Effectively Math.max(MAX_DOUBLE_ERROR, FLOAT_STEP_AT_ONE)
        // but since FLOAT_STEP is always larger in MC coordinates, we just use it.

        if (cursor.x > maxBound + upperTolerance ||
                cursor.y > maxBound + upperTolerance ||
                cursor.z > maxBound + upperTolerance) {

            // Alert logic
            double limit = maxBound + upperTolerance;
            var buf = V.write(verbose()).cursor((float) cursor.x, (float) cursor.y, (float) cursor.z).f64(limit);
            if (flag(buf) && shouldModifyPackets() && shouldCancel()) {
                place.resync();
            }
        }
    }

}
