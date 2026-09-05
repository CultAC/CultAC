package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.PowderSnowContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockPowderSnowContactResolverTest {
    @Test
    public void embeddedPlayerContactDoesNotDependOnLeatherBoots() {
        BlockCollisionWorld world = powderSnowWorld(0);
        Vec3d feet = new Vec3d(0.5D, 0.0D, 0.5D);

        PowderSnowContactState withoutBoots = contact(world, feet, false);
        PowderSnowContactState withBoots = contact(world, feet, true);

        assertTrue(withoutBoots.actorIntersection());
        assertTrue(withBoots.actorIntersection());
        assertFalse(withoutBoots.rawAtFeetAscendable());
        assertTrue(withBoots.rawAtFeetAscendable());
    }

    @Test
    public void powderSnowBelowCurrentHeightIsNotAscendable() {
        PowderSnowContactState contact = contact(
            powderSnowWorld(0),
            new Vec3d(0.5D, 1.0D, 0.5D),
            true
        );

        assertFalse(contact.actorIntersection());
        assertTrue(contact.feetSurface());
        assertFalse(contact.rawAtFeetAscendable());
    }

    @Test
    public void embeddedPowderSnowAlwaysAppliesInsideBlockSlowdown() {
        BlockMovementSlowdownState slowdown = BedrockBlockMovementSlowdownResolver.fromBlockWorld(
            0.6D,
            1.8D,
            new Vec3d(0.5D, 0.0D, 0.5D),
            powderSnowWorld(0),
            false
        );

        assertEquals(BlockMovementSlowdownState.POWDER_SNOW, slowdown);
    }

    @Test
    public void persistedBlockClimberFlagUsesPointOneFiveBranch() {
        BedrockMobJump blockClimberAscend = BedrockMobJump.resolve(
            jumpInput(true, false, false),
            BedrockMobJumpComponentState.DEFAULT
        );

        assertEquals(BedrockMobJump.Branch.SCAFFOLDING_OR_ASCENDABLE_BLOCK, blockClimberAscend.branch());
        assertEquals(0.22500000894069672D, BlockMovementSlowdownState.POWDER_SNOW.applyToMoveRequest(
            blockClimberAscend.applyVelocityMutation(Vec3d.ZERO)).y(), 1.0E-15D);
    }

    @Test
    public void rawPowderSnowAtFeetUsesPointTwoFallback() {
        BedrockMobJump rawPowderAscend = BedrockMobJump.resolve(
            jumpInput(false, true, false),
            BedrockMobJumpComponentState.DEFAULT
        );

        assertEquals(BedrockMobJump.Branch.LADDER_OR_POWDER_SNOW_AT_FEET, rawPowderAscend.branch());
        assertEquals(0.30000001192092896D, BlockMovementSlowdownState.POWDER_SNOW.applyToMoveRequest(
            rawPowderAscend.applyVelocityMutation(Vec3d.ZERO)).y(), 1.0E-15D);
    }

    @Test
    public void persistedBlockClimberFlagPrecedesRawPowderFallback() {
        BedrockMobJump jump = BedrockMobJump.resolve(
            jumpInput(true, true, false),
            BedrockMobJumpComponentState.DEFAULT
        );

        assertEquals(BedrockMobJump.Branch.SCAFFOLDING_OR_ASCENDABLE_BLOCK, jump.branch());
    }

    @Test
    public void rawPowderPredicateUsesCenterBlockNotActorFootprint() {
        BlockCollisionWorld world = powderSnowWorld(0);
        Vec3d feetWithSideOverlap = new Vec3d(1.05D, 0.0D, 0.5D);

        PowderSnowContactState rawContact = contact(world, feetWithSideOverlap, true);

        assertTrue(rawContact.actorIntersection());
        assertFalse(rawContact.rawAtFeetAscendable());
    }

    private static PowderSnowContactState contact(
        BlockCollisionWorld world,
        Vec3d feet,
        boolean canStandOnSnow
    ) {
        return BedrockPowderSnowContactResolver.fromBlockWorld(
            0.6D, 1.8D, feet, world, canStandOnSnow);
    }

    private static BedrockMobJumpInput jumpInput(
        boolean ascendableBlock,
        boolean rawPowderAtFeet,
        boolean groundJump
    ) {
        return new BedrockMobJumpInput(
            true,
            false,
            false,
            ascendableBlock,
            rawPowderAtFeet,
            false,
            false,
            groundJump
        );
    }

    private static BlockCollisionWorld powderSnowWorld(int y) {
        BlockPosition position = new BlockPosition(0, y, 0);
        return new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
            position,
            "minecraft:powder_snow",
            "minecraft:powder_snow",
            List.of(),
            Set.of(BlockContactBehavior.POWDER_SNOW)
        )));
    }
}
