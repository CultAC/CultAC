package ac.grim.grimac.utils.nmsutil;


public class Above {
    public static boolean isAbove(double y) {
        return y > Math.floor(y) + 0.5 - 1.0E-5F;
    }
}
