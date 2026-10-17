package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.player.CultPlayer;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

class ProvenanceRingTest {
    @Test
    void eachPingCultacWritesIsKnownByItsWireIdUntilItLeavesTheRing() {
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.PROXY, true)) {
            CultPlayer player = fixture.player;
            player.recordProvenance(true);

            player.sendTransaction();
            int leading = player.lastTransactionSent.get();
            Integer leadingWire = player.wireIdOf(leading);
            assertNotNull(leadingWire);
            assertEquals(CultPlayer.PING_LEADING, player.pingProvenance(leadingWire));

            CultPlayer.TrackedTransaction deferred = player.createTrackedTransactionPacketForDeferredSend();
            assertNotNull(deferred);
            Integer trailingWire = player.wireIdOf(deferred.transaction());
            assertNotNull(trailingWire);
            assertEquals(CultPlayer.PING_TRAILING, player.pingProvenance(trailingWire));
            player.markTrackedTransactionPacketSent(deferred);

            player.sendEndOfTickTransaction(42);
            Integer endOfTick = player.wireIdOf(player.lastTransactionSent.get());
            assertNotNull(endOfTick);
            assertEquals(42L << 8 | CultPlayer.PING_END_OF_TICK, player.pingProvenance(endOfTick));

            assertNull(player.wireIdOf(0));
            assertNull(player.wireIdOf(player.lastTransactionSent.get() + 1));

            for (int i = 0; i < 128; i++) {
                player.sendTransaction();
                drain(fixture);
            }
            assertNull(player.wireIdOf(leading));
            assertEquals(0, player.pingProvenance(leadingWire));
            assertEquals(
                    CultPlayer.PING_LEADING, player.pingProvenance(player.wireIdOf(player.lastTransactionSent.get())));
        }
    }

    @Test
    void aWireIdCultacNeverWroteIsUnknown() {
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.SERVER, true)) {
            fixture.player.recordProvenance(true);
            fixture.player.sendTransaction();
            int wire = fixture.player.wireIdOf(fixture.player.lastTransactionSent.get());
            assertEquals(0, fixture.player.pingProvenance(wire + 1));
        }
    }

    private static void drain(CaptureFixture fixture) {
        Object written;
        while ((written = fixture.channel.readOutbound()) != null) ReferenceCountUtil.release(written);
    }
}
