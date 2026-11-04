package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.player.CultPlayer;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AlertDetailsTest {
    @Test
    void bedrockSensitivityIsUnknownInTheModelAndDisplay() {
        CultPlayer player = mock(CultPlayer.class);
        when(player.isBedrockMovement()).thenReturn(true);
        when(player.getHorizontalSensitivity()).thenCallRealMethod();
        when(player.getVerticalSensitivity()).thenCallRealMethod();
        assertTrue(Double.isNaN(player.getHorizontalSensitivity()));
        assertTrue(Double.isNaN(player.getVerticalSensitivity()));
        assertEquals("n/a", AlertDetails.sensitivity(player.getHorizontalSensitivity(), false));
        assertEquals("n/a", AlertDetails.sensitivity(player.getVerticalSensitivity(), true));
        assertEquals("100", AlertDetails.sensitivity(0.5, false));
        assertEquals("0.50000", AlertDetails.sensitivity(0.5, true));
    }

    @Test
    void worldCoordinatesUseOneDecimalRegardlessOfServerLocale() {
        CultPlayer player = mock(CultPlayer.class);
        player.world = "minecraft:overworld";
        player.x = -12.34;
        player.y = 64;
        player.z = 0.16;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("overworld (-12.3 64.0 0.2)", AlertDetails.worldLocation(player));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
