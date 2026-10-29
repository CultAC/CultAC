package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.FarBreak;
import ac.cult.cultac.checks.impl.breaking.RotationBreak;
import ac.cult.cultac.checks.impl.scaffolding.FarPlace;
import ac.cult.cultac.checks.impl.scaffolding.RotationPlace;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.HitData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BambooStalkBlock#getShape moves the large-leaf column by BlockBehaviour#getOffset, so at x=0 z=0 its west face is
 * at x=-0.0625. LocalPlayer#pick clips the outline along max(block_interaction_range, entity_interaction_range) and
 * keeps a block hit closer than block_interaction_range. The positions are typefast's setup from issue #186, moved
 * down onto the fixture's floor.
 */
class OffsetBlockPickTest {
    private static final BlockPos BAMBOO = new BlockPos(0, 65, 0);
    private static final BlockPos SCAFFOLDING = new BlockPos(0, 65, 0);
    private static final Vec3 PLACE_FEET = new Vec3(-4.53, 64, 0.25);
    private static final Vec3 BREAK_FEET = new Vec3(-4.40, 64, 1.38);

    @Test
    void theLargeLeafOutlineReachesPastTheBlocksWestFace() throws Exception {
        try (var f = bamboo(6)) {
            List<SimpleCollisionBox> outline = new ArrayList<>();
            ClientBlockShapes.visual(f.player, f.player.compensatedWorld.getBlockStateIdAt(BAMBOO), 0, 65, 0)
                    .downCast(outline);
            assertEquals(1, outline.size());
            assertEquals(-0.0625, outline.get(0).minX, 1e-9);
        }
    }

    @Test
    void anExtendedEntityRangePicksTheOffsetOutlineToPlaceAgainst() throws Exception {
        try (var f = bamboo(6)) {
            stand(f, PLACE_FEET, -90);
            var place = place(f);
            f.player.checkManager.getCheck(FarPlace.class).onBlockPlace(place);
            f.player.checkManager.getCheck(RotationPlace.class).onPostFlyingBlockPlace(place);
            assertEquals(0, f.player.checkManager.getCheck(FarPlace.class).getViolations());
            assertEquals(0, f.player.checkManager.getCheck(RotationPlace.class).getViolations());
        }
    }

    @Test
    void theDefaultPickRangeEndsBeforeTheBlocksCell() throws Exception {
        try (var f = bamboo(3)) {
            stand(f, PLACE_FEET, -90);
            f.player.checkManager.getCheck(RotationPlace.class).onPostFlyingBlockPlace(place(f));
            assertEquals(1, f.player.checkManager.getCheck(RotationPlace.class).getViolations());
        }
    }

    @Test
    void anExtendedEntityRangePicksTheOffsetOutlineToBreak() throws Exception {
        try (var f = bamboo(6)) {
            stand(f, PLACE_FEET, -90);
            f.player.checkManager.getCheck(FarBreak.class).onBlockBreak(dig(f));
            assertEquals(0, f.player.checkManager.getCheck(FarBreak.class).getViolations());

            stand(f, BREAK_FEET, -104);
            f.player.checkManager.getCheck(RotationBreak.class).onPostFlyingBlockBreak(dig(f));
            assertEquals(0, f.player.checkManager.getCheck(RotationBreak.class).getViolations());
        }
    }

    @Test
    void theDefaultPickRangeBreaksNothingFromTheBreakSpot() throws Exception {
        try (var f = bamboo(3)) {
            stand(f, BREAK_FEET, -104);
            f.player.checkManager.getCheck(RotationBreak.class).onPostFlyingBlockBreak(dig(f));
            assertEquals(1, f.player.checkManager.getCheck(RotationBreak.class).getViolations());
        }
    }

    @Test
    void aHotbarKeyAfterThePickKeepsTheFullScaffoldingOutlineOfTheTicksSelection() throws Exception {
        try (var f = scaffolding()) {
            f.player.checkManager.dispatchEarlyReceive(new PacketReceiveEvent<>(
                    f.fixture.user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.SET_CARRIED_ITEM,
                    new ServerboundSetCarriedItem(1)));
            f.player.getInventory().selectHotbarSlot(1);
            var place = scaffoldingPlace(f);
            f.player.checkManager.getCheck(FarPlace.class).onBlockPlace(place);
            f.player.checkManager.getCheck(RotationPlace.class).onPostFlyingBlockPlace(place);
            f.player.checkManager.getCheck(RotationBreak.class).onPostFlyingBlockBreak(scaffoldingDig(f));
            assertEquals(0, f.player.checkManager.getCheck(FarPlace.class).getViolations());
            assertEquals(0, f.player.checkManager.getCheck(RotationPlace.class).getViolations());
            assertEquals(0, f.player.checkManager.getCheck(RotationBreak.class).getViolations());
        }
    }

    @Test
    void dirtHeldAtThePickSeesOnlyTheScaffoldingPolesAndSlab() throws Exception {
        try (var f = scaffolding()) {
            f.player.getInventory().selectHotbarSlot(1);
            f.player.checkManager.getCheck(RotationPlace.class).onPostFlyingBlockPlace(scaffoldingPlace(f));
            f.player.checkManager.getCheck(RotationBreak.class).onPostFlyingBlockBreak(scaffoldingDig(f));
            assertEquals(1, f.player.checkManager.getCheck(RotationPlace.class).getViolations());
            assertEquals(1, f.player.checkManager.getCheck(RotationBreak.class).getViolations());
        }
    }

    private static JavaTickFixture scaffolding() throws Exception {
        var f = new JavaTickFixture();
        f.player.setExperimentalChecks(true);
        int scaffolding =
                DataTables.defaults().registry().block("minecraft:scaffolding").defaultState();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, 64, 0), scaffolding);
        f.player.compensatedWorld.updateBlock(SCAFFOLDING, scaffolding);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(36, OfflineCultTestBootstrap.item("minecraft:scaffolding"));
        storage.setItem(37, OfflineCultTestBootstrap.item("minecraft:dirt"));
        f.player.getInventory().selectHotbarSlot(0);
        stand(f, new Vec3(-2, 64, 0.5), -90);
        return f;
    }

    private static BlockPlace scaffoldingPlace(JavaTickFixture f) {
        int state = f.player.compensatedWorld.getBlockStateIdAt(SCAFFOLDING);
        return new BlockPlace(
                f.player,
                Hand.MAIN_HAND,
                SCAFFOLDING,
                Direction.WEST,
                OfflineCultTestBootstrap.item("minecraft:dirt"),
                new HitData(SCAFFOLDING, new Vector3dm(0, 65.62, 0.5), Direction.WEST, state));
    }

    private static BlockBreak scaffoldingDig(JavaTickFixture f) {
        return new BlockBreak(
                f.player,
                SCAFFOLDING,
                Direction.WEST,
                Direction.WEST.ordinal(),
                PlayerAction.START_DESTROY_BLOCK,
                1,
                f.player.compensatedWorld.getBlockStateIdAt(SCAFFOLDING));
    }

    private static JavaTickFixture bamboo(double entityRange) throws Exception {
        var f = new JavaTickFixture();
        f.player.setExperimentalChecks(true);
        var registry = DataTables.defaults().registry();
        var world = f.player.compensatedWorld;
        world.updateBlock(
                new BlockPos(0, 63, 0), registry.block("minecraft:dirt").defaultState());
        world.updateBlock(
                new BlockPos(0, 64, 0), registry.block("minecraft:bamboo").defaultState());
        world.updateBlock(
                BAMBOO, registry.with(registry.block("minecraft:bamboo").defaultState(), "leaves", "large"));
        for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot("minecraft:entity_interaction_range", entityRange, List.of())));
        return f;
    }

    private static void stand(JavaTickFixture f, Vec3 feet, float yaw) {
        f.place(feet, Vec3.ZERO);
        f.player.xRot = f.player.lastTickXRot = yaw;
        f.player.yRot = f.player.lastTickYRot = 0;
    }

    private static BlockPlace place(JavaTickFixture f) {
        int state = f.player.compensatedWorld.getBlockStateIdAt(BAMBOO);
        return new BlockPlace(
                f.player,
                Hand.MAIN_HAND,
                BAMBOO,
                Direction.WEST,
                OfflineCultTestBootstrap.item("minecraft:stone"),
                new HitData(BAMBOO, new Vector3dm(-0.0625, 0.62, 0.25), Direction.WEST, state));
    }

    private static BlockBreak dig(JavaTickFixture f) {
        return new BlockBreak(
                f.player,
                BAMBOO,
                Direction.WEST,
                Direction.WEST.ordinal(),
                PlayerAction.START_DESTROY_BLOCK,
                1,
                f.player.compensatedWorld.getBlockStateIdAt(BAMBOO));
    }
}
