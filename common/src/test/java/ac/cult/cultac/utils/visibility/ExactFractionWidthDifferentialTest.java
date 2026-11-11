package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link ExactFraction}'s long terms against the all-BigInteger parent on generated operands, and on
 * operand files named by CULT_FRACTION_OPERANDS (a directory of {@code n/d n/d} hexadecimal lines).
 */
class ExactFractionWidthDifferentialTest {
    @Test
    void generatedOperandsMatchParent() {
        for (long seed = 1; seed <= 4; seed++) {
            var counts = ExactFractionWidthDifferential.randomPairs(seed, 250_000);
            assertTrue(counts.small > 50_000 && counts.wide > 50_000 && counts.mixed > 50_000, counts.toString());
        }
    }

    @Test
    void recordedOperandsMatchParent() throws Exception {
        String directory = System.getenv("CULT_FRACTION_OPERANDS");
        assumeTrue(directory != null && !directory.isBlank());
        try (var files = Files.list(Path.of(directory))) {
            for (Path file : files.sorted().toList()) {
                var counts = ExactFractionWidthDifferential.recorded(file);
                System.out.println(file.getFileName() + ": " + counts);
            }
        }
    }
}
