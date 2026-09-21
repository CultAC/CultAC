package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.state.BedrockHorseProperties;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import java.util.List;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import java.util.Set;

/** Owned by the compensated vehicle entity, so an entity replacement cannot inherit its motion. */
public record BedrockVehiclePredictionState(PredictionCommit commit, PredictionResult previousPrediction) {
    public static void initializeBoat(PacketEntity vehicle, Vec3d feet, Vec3d velocity, float yaw,
                                      BedrockCoordinateFrame coordinates) {
        var state = BedrockMovementState.fromPhysicalFeet(feet, velocity, BedrockInputFrame.idle(0),
                BedrockCollisionFlags.AIR, Medium.AIR, coordinates)
                .withBoat(BedrockBoatState.initial(vehicle)).withPhysicalFeetPosition(feet, 0)
                .withRotation(yaw, 0).withPlayerDimensions(vehicle.bedrockBoat.dimensions(), true);
        var entries = List.of(new BedrockProfileState.Entry(state,
                ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState.DEFAULT));
        vehicle.bedrockPrediction = new BedrockVehiclePredictionState(new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries)), null);
        commitTransform(vehicle, vehicle.bedrockPrediction.commit().carry());
    }

    public static void applyVelocity(PacketEntity vehicle, Vec3 velocity) {
        if (vehicle.bedrockPrediction == null) return;
        var entries = BedrockProfileState.profileEntries(vehicle.bedrockPrediction.commit().carry()).stream()
                .map(entry -> entry.withState(entry.state().withVelocityAndCollisionFlags(
                        BedrockVectorAdapter.toBedrock(velocity), entry.state().collisionFlags())))
                .toList();
        vehicle.bedrockPrediction = new BedrockVehiclePredictionState(new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries)),
                vehicle.bedrockPrediction.previousPrediction());
    }

    public static void rebase(PacketEntity vehicle, BedrockCoordinateFrame frame) {
        if (vehicle.bedrockPrediction == null) return;
        var entries = BedrockProfileState.profileEntries(vehicle.bedrockPrediction.commit().carry()).stream()
                .map(entry -> entry.withState(entry.state().withCoordinateFrame(frame)
                        .withRotation(vehicle.clientPhysicalYaw + (vehicle.isBoat() ? 90.0F : 0.0F), vehicle.clientPhysicalPitch)
                        .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(vehicle.clientPhysicalPosition), 0.0D)
                        .withVelocityAndCollisionFlags(BedrockVectorAdapter.toBedrock(vehicle.deltaMovement),
                                entry.state().collisionFlags().withOnGround(vehicle.onGround))))
                .toList();
        vehicle.bedrockPrediction = new BedrockVehiclePredictionState(
                new PredictionCommit(new BedrockNextTickStates(entries), Set.of(vehicle.deltaMovement)), null);
    }

    public static void tickRemote(PacketEntityHorse vehicle) {
        var entries = BedrockProfileState.profileEntries(vehicle.bedrockPrediction.commit().carry()).stream()
                .map(entry -> {
                    var state = entry.state();
                    var controller = state.horse().forFrame(vehicle.horseFlagsRevision, vehicle.isRearing, -1)
                            .afterTravel(state.collisionFlags().onGround(), vehicle.onGround);
                    return entry.withState(state.withHorse(controller)
                            .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(vehicle.clientPhysicalPosition), 0.0D)
                            .withRotation(vehicle.clientPhysicalYaw + (vehicle.isBoat() ? 90.0F : 0.0F), vehicle.clientPhysicalPitch)
                            .withVelocityAndCollisionFlags(BedrockVectorAdapter.toBedrock(vehicle.deltaMovement),
                                    state.collisionFlags().withOnGround(vehicle.onGround)));
                }).toList();
        vehicle.bedrockPrediction = new BedrockVehiclePredictionState(new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries)), null);
        if (!entries.isEmpty()) {
            vehicle.standAnimO = vehicle.standAnim;
            vehicle.standAnim = entries.getFirst().state().horse().standAmount();
        }
    }

    public static void commitTransform(PacketEntity vehicle, PredictionCarry carry) {
        BedrockMovementState state = BedrockProfileState.previousState(carry);
        if (state == null || !state.isVehicle()
                || (state.isBoat() ? state.boat().actor() : state.horse().actor()) != vehicle) return;
        Vec3 position = BedrockVectorAdapter.toJava(state.physicalFeetPosition());
        vehicle.setPositionRaw(GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z,
                (float) state.playerDimensions().width(), (float) state.playerDimensions().height()));
        vehicle.clientPhysicalPositionExact = true;
        vehicle.clientPhysicalYaw = state.inputFrame().yaw() - (state.isBoat() ? 90.0F : 0.0F);
        vehicle.clientPhysicalPitch = state.inputFrame().pitch();
        vehicle.deltaMovement = BedrockVectorAdapter.toJava(state.velocity());
        vehicle.onGround = state.collisionFlags().onGround();
        if (vehicle instanceof PacketEntityHorse living) {
            living.standAnimO = living.standAnim;
            living.standAnim = state.horse().standAmount();
        }
    }

    public static Vec3 passengerPosition(BedrockPredictionResult prediction) {
        // Passenger placement precedes the vehicle movement tick.
        return passengerPosition(prediction.movementResult().previousState());
    }

    public static Vec3 passengerPosition(BedrockMovementState vehicle) {
        Vec3d local = vehicle.coordinateFrame().toLocal(vehicle.physicalFeetPosition());
        if (vehicle.isBoat()) {
            Vec3d seat = vehicle.boat().properties().seat();
            float angle = vehicle.inputFrame().yaw() * BedrockMath.DEGREES_TO_RADIANS;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            return BedrockVectorAdapter.toJava(vehicle.coordinateFrame().toWorld(new Vec3d(
                    (float) local.x() + ((float) seat.x() * cos - (float) seat.z() * sin),
                    ((float) (local.y() + vehicle.packetYOffset()) + (float) seat.y())
                            - BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET,
                    (float) local.z() + ((float) seat.x() * sin + (float) seat.z() * cos))));
        }
        float animation = vehicle.horse().standAmount();
        float yaw = vehicle.inputFrame().yaw() * BedrockMath.DEGREES_TO_RADIANS;
        float seat = BedrockHorseProperties.riderSeatHeight(vehicle.horse().actor().type,
                (float) vehicle.playerDimensions().height());
        float x = (float) local.x() + 0.7F * animation * (float) Math.sin(yaw);
        float y = ((float) local.y() + seat) + 0.15F * animation;
        float z = (float) local.z() - 0.7F * animation * (float) Math.cos(yaw);
        return BedrockVectorAdapter.toJava(vehicle.coordinateFrame().toWorld(new Vec3d(x,
                y - BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET, z)));
    }

}
