package ac.cult.blocksim.data;

/** Merchant pricing preserves integer overflow, float multiplication and final clamp order. */
public final class MerchantPrices {
    private MerchantPrices() {}

    public static int count(int base, int demand, float multiplier, int special, int maximum) {
        int increase = Math.max(0, (int)Math.floor(base * demand * multiplier));
        return Math.min(Math.max(base + increase + special, 1), maximum);
    }
}
