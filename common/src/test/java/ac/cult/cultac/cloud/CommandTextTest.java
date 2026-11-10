package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CommandTextTest {
    @Test
    void plainTextLosesTagsAndFormatCodes() {
        assertEquals("red>red/red> lbold", CommandText.plain("<red>red</red> &lbold"));
        assertEquals("ctext", CommandText.plain("§ctext"));
        assertEquals("cctext", CommandText.plain("<&cctext"));
        assertEquals("fly chacks", CommandText.plain("fly &chacks"));
        assertEquals("ctext", CommandText.plain("§§ctext"));
        assertEquals("red>text", CommandText.plain("<<red>text"));
    }

    @Test
    void plainTextKeepsOrdinaryPunctuation() {
        String text = "steps=12 fraction=0.85 yaw/pitch: 3.5 > 2, 50% (high) & fast! \"quoted\" it's -1.5 a+b";
        assertEquals(text, CommandText.plain(text));
        for (String words : new String[] {"R&D", "Q&A", "Hacking & abuse", "R&D &&c"}) {
            assertEquals(words, CommandText.plain(words));
        }
    }

    @Test
    void wordsThatReadAsFlagsGainAQuote() {
        assertEquals("'-s detail '--force x", CommandText.unflagged("-s detail --force x"));
        assertEquals("delta=-0.5 range -3 a-b", CommandText.unflagged("delta=-0.5 range -3 a-b"));
    }
}
