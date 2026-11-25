package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.ItemUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The refreshed last main-hand copy leaves every result and every later comparison as the reference leaves them. */
class ClientAttackStrengthEquivalenceTest {
    /** Reference: ClientAttackStrength without the refresh, the methods this test drives, verbatim. */
    static final class Reference {
        private static final int SATURATED = 1 << 20;
        private int minTicker;
        private int maxTicker;
        private boolean possibleMiss;
        private boolean possibleMainHandChange;
        private SimItemStack lastMainHand = SimItemStack.EMPTY;

        void onClientTick(SimItemStack mainHand, boolean claimedSlotChanged) {
            onClientTickEnd();
            minTicker = Math.min(minTicker + 1, SATURATED);
            maxTicker = Math.min(maxTicker + 1, SATURATED);
            if (possibleMainHandChange) minTicker = 0;
            possibleMainHandChange = false;
            if (!lastMainHand.matches(mainHand)) {
                if (!isSameItem(lastMainHand, mainHand)) {
                    minTicker = 0;
                    if (claimedSlotChanged) maxTicker = Math.min(maxTicker, 1);
                }
                lastMainHand = mainHand.copy();
            }
        }

        void onClaimedMainHand(SimItemStack mainHand) {
            if (!isSameItem(lastMainHand, mainHand)) {
                minTicker = 0;
                possibleMainHandChange = true;
            }
        }

        void onReset() {
            minTicker = 0;
            maxTicker = 0;
        }

        void onPossibleReset() {
            minTicker = 0;
        }

        void onPossibleMiss() {
            possibleMiss = true;
        }

        void onEntityAttack() {
            possibleMiss = false;
        }

        void onClientTickEnd() {
            if (possibleMiss) minTicker = 0;
            possibleMiss = false;
        }

        void reset() {
            onReset();
            possibleMiss = false;
            possibleMainHandChange = false;
            lastMainHand = SimItemStack.EMPTY;
        }

        private static boolean isSameItem(SimItemStack first, SimItemStack second) {
            return first.itemKey().equals(second.itemKey());
        }
    }

    private static SimItemStack damaged(String key, int damage) {
        var stack = ItemUtil.modelItems().stack(key, 1);
        stack.setComponent("minecraft:damage", new NbtValue.Numeric(NbtValue.Kind.INT, damage));
        return stack;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void randomTicksKeepReferenceResultsAndComparisons() throws ReflectiveOperationException {
        var random = new Random(20);
        List<SimItemStack> pool = new ArrayList<>();
        for (int i = 0; i < 3; i++) pool.add(damaged("minecraft:diamond_sword", 3));
        pool.add(damaged("minecraft:diamond_sword", 4));
        pool.add(ItemUtil.modelItems().stack("minecraft:diamond_sword", 1));
        pool.add(damaged("minecraft:diamond_axe", 3));
        pool.add(ItemUtil.modelItems().stack("minecraft:golden_apple", 1));
        pool.add(ItemUtil.modelItems().stack("minecraft:golden_apple", 2));
        pool.add(ItemUtil.modelItems().stack("minecraft:diamond_sword", 0));
        pool.add(SimItemStack.EMPTY);
        int refreshed = 0;
        for (int run = 0; run < 200; run++) {
            var base = new Reference();
            var strength = new ClientAttackStrength();
            for (int step = 0; step < 400; step++) {
                var stack = pool.get(random.nextInt(pool.size()));
                int event = random.nextInt(24);
                if (event < 12) {
                    var before = field(strength, "lastMainHand");
                    boolean claimed = random.nextBoolean();
                    base.onClientTick(stack, claimed);
                    strength.onClientTick(stack, claimed);
                    if (before != field(strength, "lastMainHand") && base.lastMainHand.matches((SimItemStack) before)) refreshed++;
                } else if (event < 14) {
                    base.onClaimedMainHand(stack);
                    strength.onClaimedMainHand(stack);
                } else if (event < 16 && stack != SimItemStack.EMPTY) {
                    // The held instance changes in place, as a server slot update does.
                    if (random.nextBoolean()) stack.setCount(random.nextInt(3));
                    else stack.setComponent("minecraft:damage", new NbtValue.Numeric(NbtValue.Kind.INT, 3 + random.nextInt(2)));
                } else if (event < 18) {
                    base.onReset();
                    strength.onReset();
                } else if (event < 19) {
                    base.reset();
                    strength.reset();
                } else if (event < 20) {
                    base.onPossibleMiss();
                    strength.onPossibleMiss();
                } else if (event < 21) {
                    base.onEntityAttack();
                    strength.onEntityAttack();
                } else if (event < 22) {
                    base.onClientTickEnd();
                    strength.onClientTickEnd();
                } else if (event < 23) {
                    base.onPossibleReset();
                    strength.onPossibleReset();
                } else {
                    pool.set(random.nextInt(pool.size() - 2), damaged(random.nextBoolean() ? "minecraft:diamond_sword" : "minecraft:diamond_axe", 3));
                }
                String where = "run " + run + " step " + step;
                assertEquals(base.minTicker, field(strength, "minTicker"), where);
                assertEquals(base.maxTicker, field(strength, "maxTicker"), where);
                assertEquals(base.possibleMiss, field(strength, "possibleMiss"), where);
                assertEquals(base.possibleMainHandChange, field(strength, "possibleMainHandChange"), where);                var last = (SimItemStack) field(strength, "lastMainHand");
                assertTrue(base.lastMainHand.matches(last) && last.matches(base.lastMainHand), where);
                for (var other : pool) assertEquals(base.lastMainHand.matches(other), last.matches(other), where);
            }
        }
        assertTrue(refreshed > 0, "an equal stack with other component instances was seen");
    }
}
