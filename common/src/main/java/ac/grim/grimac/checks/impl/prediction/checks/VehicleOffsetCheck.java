package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.NumFormatter;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public class VehicleOffsetCheck implements EngineCheck {
    private static final double VEHICLE_OFFSET_THRESHOLD = 0.005D;

    @Override
    public void handleResult(GrimPlayer player, PredictionResult result, PredictionResult lastResult) {
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (vehicle == null) {
            return;
        }

        Vec3 closest = result.getAcceptedClosestToTarget();
        Vec3 target = result.getTarget();
        Vec3 offset = target.subtract(closest);
        double severity = offset.length();
        if (severity > VEHICLE_OFFSET_THRESHOLD) {
            String vehicleType = EntityTypeUtil.getKey(vehicle.type).toString();
            result.addFlag(player.checkManager.getListener(VehicleOffset.class),
                    () -> "type=" + vehicleType + " offset=" + formatOffset(offset),
                    severity);
        }
    }

    private String formatOffset(Vec3 offset) {
        return "(x=" + formatAxis(offset.x)
                + ", y=" + formatAxis(offset.y)
                + ", z=" + formatAxis(offset.z) + ")";
    }

    private String formatAxis(double value) {
        if (Math.abs(value) < 0.001D) {
            return NumFormatter.formatNumberStandard(value).trim();
        }

        String formatted = String.format(Locale.ROOT, "%.6f", value);
        int end = formatted.length();
        while (end > 0 && formatted.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && formatted.charAt(end - 1) == '.') {
            end--;
        }
        return formatted.substring(0, end);
    }
}
