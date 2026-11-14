package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockResolvedMove;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;

public final class BedrockMoveSystems {
    private BedrockMoveSystems() {}

    public static BedrockCollisionOutput move(BedrockTravelPlan plan) {
        BedrockFrameState frame = plan.frame();
        if (!frame.input().options().travelActive()) {
            var previous = frame.input().previousState();
            // Travel is inactive on this tick, so no collision sweep is needed. The climbable
            // contact comes out of a move: with none, the contact from before the teleport stays.
            var unchanged = new BedrockEntityMove.Result(
                    previous.physicalFeetPosition(),
                    plan.travelVelocity(),
                    false,
                    false,
                    false,
                    false,
                    previous.collisionFlags(),
                    previous.movementGrounded(),
                    false,
                    false,
                    false,
                    false);
            return new BedrockCollisionOutput(unchanged, previous.climbableContact(), plan.moveRequest(), null);
        }
        BedrockMoveRequest request = applyBlockMovementSlowdown(plan);
        var previous = frame.input().previousState();
        // The client fetches shapes before sneak edge movement adjusts the request.
        var fetchBox = BedrockCollisionFetchBox.of(
                previous.collisionBox(frame.frameFacts().movementDimensions()),
                request.requestedPosition().subtract(previous.physicalFeetPosition()),
                frame.input().options().maxUpStep(),
                previous.coordinateFrame());
        BedrockFrameFacts facts = frame.frameFacts();
        BlockCollisionWorld world = facts.blockCollisionWorld();
        int order = frame.input().options().solidActorOrder();
        int sneakEdges = 0;
        if (!frame.branch().glidingTravel()) {
            // The moving actor a sneaking player stands on may show its edge elsewhere: the tick's first move
            // counts those places, and the move of each meets the actor there.
            var elsewhere = BedrockSneakEdgeMovement.shownElsewhere(
                    previous,
                    frame.input().inputFrame().sneaking(),
                    facts.movementDimensions(),
                    frame.input().options().maxUpStep(),
                    request,
                    world);
            if (order == 0) {
                sneakEdges = elsewhere.size();
            } else if (!BedrockSolidActorOrders.isCollected(order)
                    && BedrockSolidActorOrders.sneakEdge(order) != 0
                    && BedrockSolidActorOrders.sneakEdge(order) <= elsewhere.size()) {
                world = BedrockSneakEdgeMovement.shown(world, elsewhere.get(BedrockSolidActorOrders.sneakEdge(order) - 1));
            }
            request = BedrockSneakEdgeMovement.applyBeforeCollision(
                    previous,
                    frame.input().inputFrame().sneaking(),
                    facts.movementDimensions(),
                    frame.input().options().maxUpStep(),
                    request,
                    world);
        }

        // A player without collision moves its box by the whole request: nothing stops it, steps it or grounds it.
        BlockCollisionWorld collisionWorld = facts.context().movementNoClip() ? BlockCollisionWorld.EMPTY : world;
        BedrockEntityMove.Result blockMove = BedrockEntityMove.move(
                frame.input().previousState(),
                frame.input().inputFrame(),
                collisionWorld,
                facts.movementDimensions(),
                request.requestedPosition(),
                request.collisionInputVelocity(),
                request.move().y(),
                frame.input().options().canStep(),
                frame.input().options().maxUpStep(),
                facts.context().movementAbilityFlying(),
                fetchBox,
                order,
                frame.input().options().actorPositionEnd());
        if (sneakEdges != 0) {
            blockMove = blockMove.withSolidActorOrder(
                    BedrockSolidActorOrders.withSneakEdges(blockMove.solidActorOrder(), sneakEdges),
                    blockMove.solidActorOrderRange());
        }
        BedrockClimbableContact nextClimbableContact = frame.boat() != null
                ? BedrockClimbableContact.NONE
                : frame.input().worldSnapshot().climbableContactAt(blockMove.position(), facts.movementDimensions());
        return new BedrockCollisionOutput(blockMove, nextClimbableContact, request, fetchBox);
    }

    private static BedrockMoveRequest applyBlockMovementSlowdown(BedrockTravelPlan plan) {
        BedrockMoveRequest request = plan.moveRequest();
        BlockMovementSlowdownState slowdown = plan.frame().frameFacts().blockMovementSlowdownState();
        if (!slowdown.active()) {
            return request;
        }
        // The slowdown scales the move and clears the velocity the actor carries into it.
        Vec3d slowedMove = slowdown.applyToMoveRequest(request.move());
        Vec3d carriedVelocity = slowdown.clearVelocityAfterMove() ? Vec3d.ZERO : slowedMove;
        BedrockResolvedMove resolvedMove =
                new BedrockResolvedMove(request.lavaSwimUpApplied(), slowedMove, carriedVelocity);
        return new BedrockMoveRequest(
                resolvedMove,
                BedrockTravelMoveRequest.requestedPosition(plan.frame().input().previousState(), slowedMove));
    }
}
