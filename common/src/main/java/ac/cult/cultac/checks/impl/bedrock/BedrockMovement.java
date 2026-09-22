package ac.cult.cultac.checks.impl.bedrock;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.checks.type.CheckListener;
import java.util.Locale;
import org.bukkit.ChatColor;

@BedrockSupported
public final class BedrockMovement extends Check implements CheckListener {
    private static final double DEFAULT_IMMEDIATE_FLAG_THRESHOLD = 0.1D;

    private double immediateFlagThreshold = DEFAULT_IMMEDIATE_FLAG_THRESHOLD;
    private Integer lastVehicleTeleportTick;
    private Integer lastVehicleMountSwitchTick;

    public BedrockMovement(CultPlayer player) {
        super(player, CheckInfo.builder()
                .name("BedrockMovement")
                .stableKey("cult.bedrock.movement")
                .description("Validates Bedrock server-authoritative movement with CultAC Bedrock prediction")
                .build());
    }

    @Override
    public void reload() {
        super.reload();
        immediateFlagThreshold = disabledToMax(
                getConfig().getDoubleElse("Simulation.immediate-setback-threshold", DEFAULT_IMMEDIATE_FLAG_THRESHOLD));
    }

    public boolean shouldEvaluateOffset(double offset, double positionThreshold) {
        return shouldFlag(offset, positionThreshold, immediateFlagThreshold);
    }

    public void flagMovement(double offset, int debugIdentifier) {
        if (canFlagMovement()) {
            flag(String.format(Locale.ROOT, "%.5f", offset) + " " + ChatColor.DARK_GRAY + debugIdentifier);
        }
    }

    public void onVehicleTeleport() {
        lastVehicleTeleportTick = player.packetStateData.acceptedClientTick;
    }

    public void onVehicleMountSwitch() {
        lastVehicleMountSwitchTick = player.packetStateData.acceptedClientTick;
    }

    public boolean canFlagMovement() {
        int tick = player.packetStateData.acceptedClientTick;
        return !player.getSetbackTeleportUtil().isPendingSetback()
                && !player.bedrockState.movementCorrections.hasPendingCorrection()
                && (lastVehicleTeleportTick == null || tick - lastVehicleTeleportTick >= 5)
                && (lastVehicleMountSwitchTick == null || tick - lastVehicleMountSwitchTick >= 5);
    }

    static double disabledToMax(double value) {
        return value == -1.0D ? Double.MAX_VALUE : value;
    }

    static boolean shouldFlag(double offset, double threshold, double immediateSetbackThreshold) {
        return offset >= threshold || offset >= immediateSetbackThreshold;
    }

}
