package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BedrockActorHistoryTest {
    @Test
    public void replayStartsAfterTheAnchorAndMatchesForwardTicksExactly() {
        var history = history(1, 8);
        List<Entry> expected = history.current();
        var anchor = history.frames().get(2).end().getFirst().state();
        var result = history.apply(3, new BedrockReplayEvent.Motion(anchor.velocity()), (state, world) -> {
            fail("Unchanged positions must retain recorded collision inputs");
            return world;
        });
        assertEquals(expected, result);
        assertEquals(8, result.getFirst().state().clientTick());
        assertEquals(8, result.getFirst().state().simulationTick());
    }

    @Test
    public void laterCorrectionsSurviveReplayingAnEarlierFrame() {
        var history = history(1, 8);
        Vec3d lastVelocity = new Vec3d(-0.12, -0.0784, 0.03);
        history.apply(7, new BedrockReplayEvent.Motion(lastVelocity), (state, world) -> world);
        history.apply(2, new BedrockReplayEvent.Motion(Vec3d.ZERO), (state, world) -> world);
        assertEquals(lastVelocity, history.frames().get(6).end().getFirst().state().velocity());
        assertEquals(1, history.frames().get(6).events().size());
        assertEquals(1, history.frames().get(1).events().size());
    }

    @Test
    public void correctionsOnTheSameFramePreserveWireOrder() {
        var history = history(1, 3);
        history.apply(2, new BedrockReplayEvent.Motion(new Vec3d(1, 2, 3)), (state, world) -> world);
        history.apply(2, new BedrockReplayEvent.Motion(new Vec3d(4, 5, 6)), (state, world) -> world);
        assertEquals(new Vec3d(4, 5, 6), history.frames().get(1).end().getFirst().state().velocity());
    }

    @Test
    public void deliveryCopyCannotRewindTheContinuingModel() {
        var authoritative = history(1, 5);
        var expected = authoritative.current();
        var correctionHistory = authoritative.copy();
        correctionHistory.apply(2, new BedrockReplayEvent.Motion(new Vec3d(0.4, 0.2, 0)), (state, world) -> world);
        assertEquals(expected, authoritative.current());
        assertNotEquals(expected, correctionHistory.current());
        correctionHistory.clear();
        assertEquals(expected, authoritative.current());
    }

    @Test
    public void oldAndMissingTicksDoNotInventInputFrames() {
        var history = history(1, 46);
        assertEquals(BedrockActorHistory.CAPACITY, history.frames().size());
        assertEquals(7, history.oldestTick());
        history.apply(1, new BedrockReplayEvent.Motion(Vec3d.ZERO), (state, world) -> world);
        assertEquals(1, history.frames().getFirst().events().size());
        long steps = history.current().getFirst().state().simulationTick();
        history.apply(1000, new BedrockReplayEvent.Motion(Vec3d.ZERO), (state, world) -> world);
        assertEquals(steps, history.current().getFirst().state().simulationTick());
        assertEquals(46, history.newestTick());
        var frame = forward(history.current().getFirst(), 49, ground(), Vec3d.ZERO);
        history.record(frame);
        assertEquals(1, history.frames().size());
        assertEquals(49, history.oldestTick());
    }

    @Test
    public void substantialReplayRelocationRefreshesCollisionInputs() {
        var history = history(1, 3);
        var anchor = history.frames().getFirst().end().getFirst().state();
        var calls = new AtomicInteger();
        history.apply(1, new BedrockReplayEvent.Reposition(anchor.physicalFeetPosition().add(new Vec3d(2, 0, 0)),
                0, 0, true, anchor.coordinateFrame()), (state, world) -> {
            calls.incrementAndGet();
            return world;
        });
        assertTrue(calls.get() > 0);
    }

    @Test
    public void historicalContextPatchStopsAtItsRecordedReceiptBoundary() {
        var history = history(1, 4);
        var original = history.current().getFirst();
        history.apply(1, new BedrockReplayContextEvent(Map.of("minecraft:movement", 0.2F),
                null, null, -1, null, null), (state, world) -> world);
        assertNotEquals(original.state().physicalFeetPosition(), history.current().getFirst().state().physicalFeetPosition());
        // This frame was captured after receipt; its context already contains subsequent updates.
        var next = forward(history.current().getFirst(), 5, ground(), new Vec3d(0, 0, 1));
        history.advance(next, (state, world) -> world);
        assertEquals(next.end(), history.current());
    }

    @Test
    public void captureVelocityInjectionConvergesAfterTheHistoricalCorrection() {
        // Regression values from the 740 correction and the subsequent 742-744 inputs.
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(289.4287F, 82, -86.09165F),
                new Vec3d(-0.08224659F, -0.0784F, 0.011113953F),
                new BedrockInputFrame(740, 82.30463F, 5.652817F, false, false, true), BedrockCollisionFlags.ON_GROUND)
                .withSprinting(true);
        var entry = new Entry(state, BedrockMobJumpComponentState.DEFAULT);
        double[][] endpoints = {{288.98013F, -86.03104F, -0.13109092F, 0.017713573F},
                {288.72278F, -85.99626F, -0.1405096F, 0.018986221F},
                {288.45602F, -85.96021F, -0.1456522F, 0.019681087F}};
        var history = new BedrockActorHistory();
        for (long tick = 741; tick <= 744; tick++) {
            var frame = forward(entry, tick, ground(), new Vec3d(0, 0, 1));
            history.record(frame);
            entry = frame.end().getFirst();
            if (tick == 741) {
                // A delivered correction does not make this old input corrected movement.
                assertTrue(entry.state().physicalFeetPosition().subtract(new Vec3d(289.35892F, 82.9116, -86.08222F)).length() > 0.5);
            } else {
                var expected = endpoints[(int) tick - 742];
                assertTrue(entry.state().physicalFeetPosition().subtract(new Vec3d(expected[0], 82, expected[1])).length() <= 0.001);
                assertTrue(entry.state().velocity().subtract(new Vec3d(expected[2], -0.0784F, expected[3])).length() <= 0.001);
            }
        }
        assertTrue(Math.abs(entry.state().velocity().x()) > 0.14);
    }

    @Test
    public void horseAndBoatReplayRetainValuesWithoutLiveActorHandles() {
        var base = BedrockMovementState.fromPhysicalFeet(new Vec3d(289, 82, -86), Vec3d.ZERO,
                BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
        var horse = org.mockito.Mockito.mock(ac.cult.cultac.utils.data.packetentity.PacketEntityHorse.class);
        var boat = org.mockito.Mockito.mock(ac.cult.cultac.utils.data.packetentity.PacketEntity.class);
        var horseState = base.withHorse(new ac.cult.cultac.bedrock.prediction.state.BedrockHorseState(
                horse, false, 0, false, false, 0, -1, false, 0));
        var boatState = base.withBoat(new ac.cult.cultac.bedrock.prediction.state.BedrockBoatState(boat,
                ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties.initial(12), 0, 0, 0,
                ac.cult.cultac.bedrock.prediction.state.BedrockBoatState.Paddle.INITIAL,
                ac.cult.cultac.bedrock.prediction.state.BedrockBoatState.Paddle.INITIAL, true));
        for (var initial : List.of(horseState, boatState)) {
            var history = new BedrockActorHistory();
            var entry = new Entry(initial, BedrockMobJumpComponentState.DEFAULT);
            for (long tick = 1; tick <= 4; tick++) {
                var frame = forward(entry, tick, ground(), new Vec3d(0, 0, 1));
                history.record(frame);
                entry = frame.end().getFirst();
            }
            var expected = history.current();
            var first = history.frames().getFirst().end().getFirst().state();
            assertNull(first.isHorse() ? first.horse().actor() : first.boat().actor());
            assertEquals(expected, history.apply(1, new BedrockReplayEvent.Motion(first.velocity()), (state, world) -> world));
            var live = BedrockReplaySnapshot.attach(history.current().getFirst().state(), initial);
            assertSame(initial.isHorse() ? horse : boat, live.isHorse() ? live.horse().actor() : live.boat().actor());
        }
    }

    static BedrockActorHistory history(long first, long last) {
        var history = new BedrockActorHistory();
        var entry = new Entry(BedrockMovementState.fromPhysicalFeet(new Vec3d(289, 82, -86),
                new Vec3d(0, -0.0784F, 0), BedrockInputFrame.idle(first - 1), BedrockCollisionFlags.ON_GROUND),
                BedrockMobJumpComponentState.DEFAULT);
        for (long tick = first; tick <= last; tick++) {
            var frame = forward(entry, tick, ground(), new Vec3d(0, 0, 1));
            history.record(frame);
            entry = frame.end().getFirst();
        }
        return history;
    }

    static BedrockActorHistory.Frame forward(Entry previous, long tick, BedrockWorldSnapshot world, Vec3d controls) {
        var input = new BedrockInputFrame(tick, 82.30463F, 5.652817F, false, false,
                previous.state().sprinting(), Set.of("UP", "SPRINTING"));
        var request = new BedrockSimulation.Input(previous.state(), input, input.intent(), world, true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, previous.mobJumpComponent(), true, false, controls);
        var movement = BedrockForwardTick.simulate(request).getFirst();
        var state = movement.movementResult().predictedState();
        var entries = BedrockForwardTick.finish(movement.movementResult(), state).stream()
                .map(next -> new Entry(next, movement.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, request, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                state.physicalFeetPosition(), state.velocity(), List.of());
    }

    static BedrockWorldSnapshot ground() {
        var floor = PlacedBlockCollision.manual(new BlockPosition(270, 81, -110), "minecraft:stone", "minecraft:stone",
                List.of(new WorldCollisionBox(270, 81, -110, 310, 82, -60)));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(floor))),
                EquipmentState.NONE, EntityContactState.NONE, MovementModifierState.NONE, PlayerDimensionsState.DEFAULT));
    }
}
