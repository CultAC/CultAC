package ac.cult.cultac.utils.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DebugTextTest {
    private enum Side {
        LEFT
    }

    private record Point(int x, double y, String name, Side side) {}

    @Test
    void printsWhatAStringBuilderWouldHavePrinted() {
        StringBuilder expected = new StringBuilder();
        DebugText.Builder text = new DebugText.Builder(1, 1);
        Object[] values = {
            "a", null, new Vec3(0.1, -0.0, 1.0E-7), Side.LEFT, new Point(-3, 2.5, "p", Side.LEFT), 7, List.of(1, 2)
        };
        for (Object value : values) {
            expected.append(value);
            text.append(value);
        }
        for (double value : new double[] {-0.0, 0.1, 1.0E-7, 1.0E300, Double.NaN, Double.NEGATIVE_INFINITY}) {
            expected.append(value).append(' ');
            text.append(value).append(' ');
        }
        for (float value : new float[] {-0.0F, 0.1F, 1.0E-7F, Float.NaN}) {
            expected.append(value).append(',');
            text.append(value).append(',');
        }
        expected.append(Integer.MIN_VALUE)
                .append(Long.MAX_VALUE)
                .append(true)
                .append('é')
                .append('☃');
        text.append(Integer.MIN_VALUE)
                .append(Long.MAX_VALUE)
                .append(true)
                .append('é')
                .append('☃');
        expected.append((String) null).append('\n');
        text.append((String) null).append('\n');

        assertEquals(expected.toString(), text.toString());
        assertEquals(expected.toString(), text.toText().toString());
    }

    @Test
    void aTextReadsAsItWasAppendedWhateverChangesAfterwards() {
        SimpleCollisionBox box = new SimpleCollisionBox(0, 0, 0, 1, 1, 1, false);
        List<String> list = new ArrayList<>(List.of("first"));
        DebugText.Builder builder = new DebugText.Builder();
        builder.append(box).append(' ').append(list).append(' ').appendAll(list);
        String expected = box + " " + list + " " + list;

        DebugText text = builder.toText();
        box.offset(5, 5, 5);
        list.add("second");
        builder.append("appended later");

        assertEquals(expected, text.toString());
        assertEquals("appended later", builder.toString());
    }

    @Test
    void aVectorPrintsAsItsToString() {
        double[] edges = {
            0.0,
            -0.0,
            1.0,
            0.1,
            -0.30000000000000004,
            1.0E-7,
            1.0E7,
            123456789.0,
            Double.MIN_VALUE,
            -Double.MAX_VALUE,
            Double.MIN_NORMAL,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        };
        List<Vec3> vectors = new ArrayList<>();
        for (double x : edges) vectors.add(new Vec3(x, -x, x / 3));
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            vectors.add(new Vec3(
                    Double.longBitsToDouble(random.nextLong()),
                    random.nextDouble() - 0.5,
                    (random.nextInt(2_000_001) - 1_000_000) / 1024.0));
        }
        StringBuilder expected = new StringBuilder();
        DebugText.Builder text = new DebugText.Builder();
        for (Vec3 vector : vectors) {
            expected.append(vector.toString()).append(';');
            text.append(vector).append(';');
        }
        expected.append(vectors);
        text.appendAll(vectors);

        assertEquals(expected.toString(), text.toString());
        assertEquals(expected.toString(), text.toText().toString());
    }
}
