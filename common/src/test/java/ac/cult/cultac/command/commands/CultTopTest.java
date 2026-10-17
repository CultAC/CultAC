package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CultTopTest {
    @Test
    void aLoneNumberIsTheMinutes() {
        assertEquals(new CultTop.Query(null, 60), CultTop.query("60", null));
    }

    @Test
    void aCheckKeepsTheDefaultWindow() {
        assertEquals(new CultTop.Query("aim.snap", CultTop.DEFAULT_MINUTES), CultTop.query("aim.snap", null));
        assertEquals(new CultTop.Query(null, CultTop.DEFAULT_MINUTES), CultTop.query(null, null));
    }

    @Test
    void aNumberOutsideTheWindowIsACheckName() {
        assertEquals(new CultTop.Query("99999", CultTop.DEFAULT_MINUTES), CultTop.query("99999", null));
        assertEquals(new CultTop.Query("aim.snap", 5), CultTop.query("aim.snap", 5));
    }
}
