package ac.grim.grimac.utils.anticheat;

import net.minecraft.world.phys.Vec3;

public class NumFormatter {
    public static String formatVector(Vec3 vector) {
        return padLeft(formatNumberStandard(vector.x)) + " " + padLeft(formatNumberStandard(vector.y)) + " " + padLeft(formatNumberStandard(vector.z));
    }

    public static String formatVectorDebug(Vec3 vector) {
        return padLeft(formatNumberDebug(vector.x)) + " " + padLeft(formatNumberDebug(vector.y)) + " " + formatNumberDebug(vector.z);
    }

    // Spaces are 3 characters
    // Numbers, minus, E, is 5 characters
    public static String padLeft(String s) {
        int numWidth = 0;
        for (char c : s.toCharArray()) {
            if (c == '.') {
                numWidth += 1;
            } else if (c == ' ') {
                numWidth += 3;
            } else {
                numWidth += 5;
            }
        }
        // Try to reach 40 width
        int spaces = (40 - numWidth) / 3;
        StringBuilder sBuilder = new StringBuilder(s);
        for (int i = 0; i < spaces; i++) {
            sBuilder.insert(0, " ");
        }
        return sBuilder.toString();
    }

    public static String formatNumberStandard(double offset) {
        double absOffset = Math.abs(offset);

        String humanFormattedOffset;
        if (absOffset < 1e-7) { // Too little to care about
            return "0";
        } else if (absOffset < 0.001) { // 1.129E-3
            humanFormattedOffset = String.format("%.4E", offset);
            // Squeeze out an extra digit here by E-03 to E-3
            humanFormattedOffset = humanFormattedOffset.replace("E-0", "E-");
        } else {
            // 0.00112945678 -> .001129
            humanFormattedOffset = String.format("%6f", offset);
            // I like the leading zero, but removing it lets us add another digit to the end
            humanFormattedOffset = humanFormattedOffset.replace("0.", ".");
        }
        return humanFormattedOffset;
    }

    public static String formatNumberDebug(double offset) {
        double absOffset = Math.abs(offset);

        String humanFormattedOffset;
        if (absOffset == 0) {
            return "0";
        } else if (absOffset < 0.001) { // 1.129E-3
            humanFormattedOffset = String.format("%.4E", offset);
            // Squeeze out an extra digit here by E-03 to E-3
            humanFormattedOffset = humanFormattedOffset.replace("E-0", "E-");
        } else {
            // 0.00112945678 -> .001129
            humanFormattedOffset = String.format("%6f", offset);
            // I like the leading zero, but removing it lets us add another digit to the end
            humanFormattedOffset = humanFormattedOffset.replace("0.", ".");
        }
        return humanFormattedOffset;
    }
}
