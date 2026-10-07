package ac.cult.blocksim.enchantment;

/** The caller's client random stream; effects neither seed nor replace it. */
public interface EffectRandom {
    float nextFloat();
    double nextGaussian();
}
