package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.aim.AimDuplicateLook;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsD;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.printer.PrinterUse;
import ac.cult.cultac.checks.impl.scaffolding.RotationPlace;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The sakura-ryoko litematica-printer's PrepareAction sends an extra PosRot at the player's position with an
 * axis-aligned look, then InteractAction sends use_item_on and use_item in one tick.
 */
class PrinterUseTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 MOVED = new Vec3(0.5, FLOOR_Y + 0.1, 0.5);
    private static final BlockPos NEAR = new BlockPos(0, FLOOR_Y - 1, 1);
    private static final BlockPos FAR = new BlockPos(20, FLOOR_Y, 0);
    private static final float YAW = 37.25f;
    private static final float PITCH = 12.5f;

    @Test
    void twoPrinterActionsActivateItAndRaiseOneAlert() throws Exception {
        try (var t = new Printer()) {
            t.action(NEAR, 180f, 0f);
            assertFalse(t.printer.isActive());
            t.action(NEAR, 90f, 0f);
            assertTrue(t.printer.isActive());
            t.action(NEAR, 0f, -90f);
            t.action(NEAR, 270f, 90f);
            assertEquals(1, t.printer.getViolations());
        }
    }

    @Test
    void anAlertRepeatsOnlyAfterTheAlertInterval() throws Exception {
        try (var t = new Printer()) {
            // One action a second: active throughout, alerted on activation and again a minute later.
            for (int second = 1; second <= 70; second++) {
                t.now += 1_000;
                t.action(NEAR, 180f, 0f);
                if (second == 61) assertEquals(1, t.printer.getViolations());
            }
            assertEquals(2, t.printer.getViolations());
        }
    }

    @Test
    void useItemOnThenUseItemWithoutASpoofedMoveIsOrdinaryPlacing() throws Exception {
        try (var t = new Printer()) {
            for (int i = 0; i < 6; i++) {
                t.move(START, YAW, PITCH);
                t.useOn(NEAR);
                t.use();
                t.tickEnd();
            }
            assertFalse(t.printer.isActive());
            assertEquals(0, t.printer.getViolations());
        }
    }

    @Test
    void anExtraMoveThatChangesPositionOrIsNotAxisAlignedIsNotASpoof() throws Exception {
        try (var t = new Printer()) {
            for (int i = 0; i < 4; i++) {
                t.move(START, YAW, PITCH);
                t.move(MOVED, 180f, 0f);
                t.move(MOVED, YAW + 1, PITCH);
                t.useOn(NEAR);
                t.use();
                t.tickEnd();
            }
            assertFalse(t.printer.isActive());
        }
    }

    @Test
    void aSpoofTooFarBeforeTheUsePairIsNotOneAction() throws Exception {
        try (var t = new Printer()) {
            for (int i = 0; i < 4; i++) {
                t.move(START, YAW, PITCH);
                t.move(START, 180f, 0f);
                t.tickEnd();
                for (int gap = 0; gap < 4; gap++) {
                    t.move(START, YAW, PITCH);
                    t.tickEnd();
                }
                t.move(START, YAW, PITCH);
                t.useOn(NEAR);
                t.use();
                t.tickEnd();
            }
            assertFalse(t.printer.isActive());
        }
    }

    @Test
    void activeStaysWithinTheHoldWindowAndLapsesAfterIt() throws Exception {
        try (var t = new Printer()) {
            t.activate();
            t.now += 1_999;
            assertTrue(t.printer.isActive());
            t.now += 2;
            assertFalse(t.printer.isActive());
            assertFalse(t.printer.absorbs(t.check(BadPacketsJ.class)));
        }
    }

    @Test
    void absorbsOnlyTheConfiguredChecksAndOnlyWhileActive() throws Exception {
        try (var t = new Printer()) {
            assertFalse(t.printer.absorbs(t.check(BadPacketsJ.class)));
            t.activate();
            assertTrue(t.printer.absorbs(t.check(BadPacketsJ.class)));
            assertTrue(t.printer.absorbs(t.check(AimDuplicateLook.class)));
            assertTrue(t.printer.absorbs(t.check(RotationPlace.class)));
            assertTrue(t.printer.absorbs(t.check(PlaceVisibility.class)));
            assertFalse(t.printer.absorbs(t.printer));
            assertFalse(t.printer.absorbs(t.check(BadPacketsD.class)));
        }
    }

    @Test
    void placementChecksAreNotAbsorbedForAClickBeyondTheMaxRange() throws Exception {
        try (var t = new Printer()) {
            t.activate();
            t.useOn(FAR);
            assertFalse(t.printer.absorbs(t.check(RotationPlace.class)));
            assertFalse(t.printer.absorbs(t.check(PlaceVisibility.class)));
            assertTrue(t.printer.absorbs(t.check(BadPacketsJ.class)));
            t.useOn(NEAR);
            assertTrue(t.printer.absorbs(t.check(RotationPlace.class)));
        }
    }

    @Test
    void lookChecksAreNotAbsorbedLongAfterTheLastSpoofedMove() throws Exception {
        try (var t = new Printer()) {
            t.activate();
            t.now += 1_500;
            t.action(NEAR, 180f, 0f);
            t.now += 1_500;
            assertTrue(t.printer.absorbs(t.check(BadPacketsJ.class)));
            t.now += 600;
            assertFalse(t.printer.absorbs(t.check(BadPacketsJ.class)));
        }
    }

    @Test
    void timerChecksAreAbsorbedOnlyForSpoofedExtraMovesWithinTheCap() throws Exception {
        try (var t = new Printer()) {
            t.activate();
            t.move(START, YAW, PITCH);
            t.move(START, 180f, 0f);
            assertTrue(t.printer.absorbs(t.check(TickTimer.class)));
            for (int i = 0; i < 4; i++) t.move(START, 90f, 0f);
            assertTrue(t.printer.absorbs(t.check(TickTimer.class)), "five extra moves is the cap");
            t.move(START, 90f, 0f);
            assertFalse(t.printer.absorbs(t.check(TickTimer.class)), "a sixth extra move is not the printer's");
            t.tickEnd();

            t.move(START, YAW, PITCH);
            t.move(MOVED, YAW + 1, PITCH);
            assertFalse(t.printer.absorbs(t.check(TickTimer.class)), "an extra move that moves is not a spoof");
        }
    }

    @Test
    void anAbsorbedTickTimerFlagLeavesTheMoveAndItsViolationsAlone() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, new Vec3(0, 0, 0));
            var printer = f.player.checkManager.getListener(PrinterUse.class);
            var t = new Printer(f, printer);
            t.activate();
            var tickTimer = f.player.checkManager.getListener(TickTimer.class);

            f.move(START, true);
            PacketReceiveEvent<ServerboundMovePlayer> extra = t.moveThroughListener(START, 180f, 0f);
            f.tickEnd();
            assertEquals(0, tickTimer.getViolations());
            assertFalse(extra.isCancelled());
        }
    }

    @Test
    void aTickTimerFlagBeforeActivationIsStillRaised() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, new Vec3(0, 0, 0));
            var t = new Printer(f, f.player.checkManager.getListener(PrinterUse.class));
            var tickTimer = f.player.checkManager.getListener(TickTimer.class);

            f.move(START, true);
            t.moveThroughListener(START, 180f, 0f);
            f.tickEnd();
            assertTrue(tickTimer.getViolations() > 0);
        }
    }

    private static final class Printer implements AutoCloseable {
        final JavaTickFixture f;
        final boolean ownsFixture;
        final PrinterUse printer;
        long now = 1_000_000;
        int sequence;

        Printer() throws Exception {
            this(new JavaTickFixture(), null);
        }

        Printer(JavaTickFixture f, PrinterUse printer) {
            this.ownsFixture = printer == null;
            this.f = f;
            this.printer = printer != null ? printer : f.player.checkManager.getListener(PrinterUse.class);
            this.printer.useClock(() -> now);
            f.place(START, new Vec3(0, 0, 0));
        }

        Check check(Class<? extends Check> type) {
            return f.player.checkManager.getCheck(type);
        }

        /** A regular tick movement, then PrepareAction's extra PosRot, then InteractAction's pair. */
        void action(BlockPos target, float spoofYaw, float spoofPitch) {
            move(START, YAW, PITCH);
            move(START, spoofYaw, spoofPitch);
            useOn(target);
            use();
            tickEnd();
        }

        /** Two printer actions in separate ticks: enough to activate it. */
        void activate() {
            action(NEAR, 180f, 0f);
            action(NEAR, 90f, 0f);
            assertTrue(printer.isActive());
        }

        void move(Vec3 to, float yaw, float pitch) {
            printer.onMovePlayer(new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, true, false, true, true));
        }

        PacketReceiveEvent<ServerboundMovePlayer> moveThroughListener(Vec3 to, float yaw, float pitch) {
            var packet = new ServerboundMovePlayer(to.x, to.y, to.z, yaw, pitch, true, false, true, true);
            var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
            f.listener.onMovePlayer(event, f.player, packet);
            return event;
        }

        void useOn(BlockPos target) {
            printer.onUseItemOn(new ServerboundUseItemOn(
                    Hand.MAIN_HAND, target, Direction.UP, new Vec3d(0.5, 1.0, 0.5), false, false, ++sequence));
        }

        void use() {
            printer.onUseItem(new ServerboundUseItem(Hand.MAIN_HAND, ++sequence, YAW, PITCH));
        }

        void tickEnd() {
            printer.onClientTickEnd();
        }

        @Override
        public void close() throws Exception {
            if (ownsFixture) f.close();
        }
    }
}
