package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/** {@link ReportedKeyInput} against {@link BaseReportedKeyInput}, by raw bits, through one reused scratch. */
public final class ReportedKeyInputComparison {
    private final ReportedKeyInput.Scratch scratch = new ReportedKeyInput.Scratch();
    public int compared;
    public int decided;
    public int nonZero;

    /** Null when both give the same offset and travel inputs, else what differs. */
    public String compare(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        compared++;
        double want = BaseReportedKeyInput.offset(player, result, lastResult);
        double got = ReportedKeyInput.offset(player, result, lastResult, scratch);
        if (want != 0.0D) nonZero++;
        if (Double.doubleToRawLongBits(want) != Double.doubleToRawLongBits(got)) {
            return "offset " + want + " != " + got;
        }
        List<Vec3> wantInputs = BaseReportedKeyInput.travelInputs(player, result, lastResult);
        List<Vec3> gotInputs = ReportedKeyInput.travelInputs(player, result, lastResult);
        if (wantInputs != null) decided++;
        String a = describe(wantInputs);
        String b = describe(gotInputs);
        return a.equals(b) ? null : "inputs " + a + " != " + b;
    }

    private static String describe(List<Vec3> inputs) {
        if (inputs == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (Vec3 input : inputs) {
            sb.append(input == Vec3.ZERO ? "ZERO" : "")
                    .append(Double.doubleToRawLongBits(input.x))
                    .append(',')
                    .append(Double.doubleToRawLongBits(input.y))
                    .append(',')
                    .append(Double.doubleToRawLongBits(input.z))
                    .append(' ');
        }
        return sb.toString();
    }
}
