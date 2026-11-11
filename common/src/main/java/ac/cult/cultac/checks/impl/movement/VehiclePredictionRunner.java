package ac.cult.cultac.checks.impl.movement;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaRootGround;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.VehicleListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import java.util.Set;

public class VehiclePredictionRunner extends CultProcessor implements VehicleListener, ClientTickEndListener {
    // The collision result that replaced this packet's disproven onGround claim.
    private Boolean correctedOnGround;

    /** The onGround a disproven claim of the last processed packet is replaced with, once; null if none. */
    public Boolean takeCorrectedOnGround() {
        Boolean corrected = correctedOnGround;
        correctedOnGround = null;
        return corrected;
    }

    public VehiclePredictionRunner(CultPlayer playerData) {
        super(playerData);
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        // LocalPlayer#sendChanges ends a controlling rider's tick with its sprint flag equal to the last command sent.
        // LocalPlayer#aiStep ends a passenger's tick with the flag cleared unless the input it sent holds forward
        // impulse (LocalPlayer#canStartSprinting, LocalPlayer#shouldStopRunSprinting).
        boolean riderSprinting = player.isClientSprinting();
        if (player.compensatedEntities.getSelf().inVehicle()
                && player.packetStateData.clientMovementInputKnown
                && !player.isBedrockMovement()) {
            riderSprinting &= player.boatData.nextVehicleKeys.forwardImpulse() > 0;
        }
        player.vehicleData.camelSprintingState = riderSprinting ? SprintingState.STARTED : SprintingState.STOPPED;
        PacketEntity root = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        if (root instanceof PacketEntityNautilus nautilus) {
            // End-of-tick interpolation runs every other nautilus's AbstractNautilus#tick but skips the locally
            // ticked root, so run that root's tail here, before the rider's release.
            if (player.compensatedEntities.vehicles.isLocallyTickedVelocityRoot(nautilus)) {
                nautilus.tickDashCooldown();
            }
            nautilus.tickPendingJump();
        }
        // ClientLevel#tickEntities runs Camel#tick for every camel, ridden or not.
        List<PacketEntityCamel> camels = player.compensatedEntities.camels();
        for (int i = 0, size = camels.size(); i < size; i++) {
            camels.get(i).tickDashCooldown();
        }
        PacketEntityHorse horse = player.compensatedEntities.vehicles.getClientVisibleHorseRoot();
        if (horse != null) {
            // MCP-Reborn AbstractHorse#tick ends with the standCounter/allowStandSliding tail after travel.
            // End-of-tick interpolation skips the locally ticked root, so run that tail here, before the
            // passenger's staged release.
            boolean locallyTicked = player.compensatedEntities.vehicles.isLocallyTickedVelocityRoot(horse);
            if (locallyTicked) {
                horse.tickStandTail();
            }
            // LocalPlayer#jumpableVehicle releases a camel rider's jump only while Camel#canJump holds after the
            // camel's tick, and Camel#canJump is false while the camel refuses to move. Camel#onPlayerJump then reads
            // the ground the camel's move and the cooldown the tickDashCooldown above left.
            if (horse instanceof PacketEntityCamel camel
                    && (camel.refuseToMove(player.compensatedWorld.endedTickGameTime()) || !camel.takesRidingJump())) {
                camel.dropRidingJump();
            }
            // MCP-Reborn ClientLevel#tickPassenger runs LocalPlayer#rideTick
            // after the root horse tick. LocalPlayer#aiStep therefore arms
            // playerJumpPendingScale for the next client horse tick, and
            // Minecraft#tick sends ClientTickEnd only after that passenger tick
            // has finished. Promote the staged release at the client-tick
            // boundary, but keep an older pending jump alive if no new release
            // happened this tick.
            if (horse.nextHorseJump > 0.0D) {
                final double stagedJump = horse.nextHorseJump;
                horse.horseJump = stagedJump;
                horse.nextHorseJump = 0.0D;
            }
            if (horse.nextAllowStandSliding) {
                horse.allowStandSliding = true;
                horse.nextAllowStandSliding = false;
            }
            // The same onPlayerJump calls standIfPossible(), which only stands a horse the client ticks itself.
            horse.promoteClientRidingJumpStand(locallyTicked);
        }

        promoteInputAfterClientTickEnd();
    }

    @Override
    public void process(final VehiclePositionUpdate vehicleUpdate) {
        correctedOnGround = null;
        // We previously didn't do vehicle setbacks because vehicle netcode sucks
        // But the only solution is to fix mojang's bugs, as otherwise bypasses and falses will occur
        PredictionResult result = player.checkManager.getSimulationProcessor().onVehiclePositionUpdate(vehicleUpdate);

        if (result == null) {
            return;
        }
        // Only LocalPlayer#tick moves of a root this client controls reach here.
        PacketEntity riding = result.getSimulationContext().getVehicle();
        if (riding instanceof PacketEntityCamel camel) {
            camel.lastPredictedInLiquid = result.getSimulationContext().getWorldData().mayBeInLiquid();
        }

        if (riding instanceof PacketEntityStrider strider) {
            var moved = result.getJavaCommittedMove();
            if (moved != null) strider.commitFluidHeights(moved.fluidHeights());
            var pass = JavaMovementEngine.passAfterTravel(result);
            if (pass != null) strider.commitFluidHeights(pass.result().fluidHeights());
        }
        evaluateItemControlledVehicle(riding);

        CollideAxisData.CollideResult downCollide = result.getCollideAxisData().getYNeg();
        boolean canCollideDown = downCollide != null && downCollide.isLikelyCollide();

        DesyncStatus lastOnGround = null;

        for (PredictionResult reality : result.getRealities()) {
            // Strider#floatStrider runs after the travel and sets the ground whether or not Entity#move landed.
            if (JavaRootGround.floatStriderGrounds(player, reality)) {
                lastOnGround = lastOnGround == null ? DesyncStatus.TRUE : lastOnGround.addBoolean(true);
                continue;
            }
            SimpleCollisionBox valid = reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
            if (!canCollideDown || valid.minY >= 0) {
                // Entity#move can report ground only after a downward collision. A nonnegative
                // maximum is insufficient: another valid pre-collision Y may still descend.
                lastOnGround = lastOnGround == null ? DesyncStatus.FALSE : lastOnGround.addBoolean(false);
            }

            // Entity#move refreshes onGround after every downward collision, including consecutive
            // grounded ticks. A client's previous onGround claim cannot suppress this proof.
            if (canCollideDown
                    && valid.maxY + 0.001 < reality.getTarget().y // And their valid Y is below their actual movement
                    && downCollide.inMovementSpace(
                                            reality.getSimulationContext().getLastStuckSpeed().y)
                                    + 0.001
                            >= reality.getTarget().y) { // And they recovered by colliding
                // Must be on the ground
                lastOnGround = lastOnGround == null ? DesyncStatus.TRUE : lastOnGround.addBoolean(true);
            }
        }

        DesyncStatus nextLastOnGround = lastOnGround == null ? DesyncStatus.UNKNOWN : lastOnGround;
        if (riding instanceof PacketEntityCamel camel
                && camel.refuseToMove(player.compensatedWorld.endedTickGameTime())
                && result.getTarget().lengthSqr() <= 1.0E-14
                && result.getAcceptedClosestToTarget().lengthSqr() <= 1.0E-14) {
            // The 26.3 seated-camel capture has both onGround values on stationary ticks. Entity#move
            // sets onGround from its requested Y, so a zero request and a downward move clipped to
            // zero have the same packet position. Geometry alone cannot distinguish those requests.
            nextLastOnGround = DesyncStatus.UNKNOWN;
        }
        if (riding != null && vehicleUpdate.isHasOnGround()) {
            boolean reported = vehicleUpdate.isOnGround();
            player.checkManager.getCheck(VehicleGround.class).onGroundClaim(reported, nextLastOnGround);
            // Every locally authoritative root sends Entity#move's post-tick onGround, so a claim disproven
            // by the collision is neither the state Paper should read nor the state the next tick starts from.
            if (VehicleGround.disproves(reported, nextLastOnGround)) {
                correctedOnGround = nextLastOnGround.determinePessimistically();
            }
            // MCP-Reborn LocalPlayer#tick sends ServerboundMoveVehiclePacket.fromEntity(rootVehicle)
            // after the root tick, and ServerboundMoveVehiclePacket#fromEntity copies the
            // root entity's post-tick Entity#onGround exactly on protocol versions
            // which carry that field. The next tick starts from this carried state. Where the
            // root's own move set a different ground, the prediction staged it.
            if (correctedOnGround == null) {
                nextLastOnGround = DesyncStatus.fromBoolean(player.packetStateData.desiredOnGroundOr(reported));
            }
        } else if (riding != null && !vehicleUpdate.isHasOnGround()) {
            Boolean ground = unreportedRootGround(result);
            if (ground != null) nextLastOnGround = DesyncStatus.fromBoolean(ground);
        }
        player.checkManager.getSimulationProcessor().setLastOnGround(nextLastOnGround);
        if (riding instanceof PacketEntityHorse && vehicleUpdate.isHasOnGround()) {
            player.getSetbackTeleportUtil().setStagedHorseOnGround(nextLastOnGround);
        }
        if (riding != null && !vehicleUpdate.isHasOnGround() && !nextLastOnGround.isDesync()) {
            // Older MoveVehicle packets omit onGround. Carry ground transitions
            // proved by Entity#move's downward collision instead of overwriting
            // the entity with the packet accessor's absent-field default.
            riding.onGround = nextLastOnGround.determinePessimistically();
        }

        if (riding != null && riding.isBoat()) {
            // AbstractBoat stores status, waterLevel, lastYd, nullifyNextY, and
            // deltaRotation on the root entity. A packet accepted from
            // LocalPlayer#tick proves that AbstractBoat#tick completed, so carry
            // those exact post-tick fields into the next vehicle prediction.
            BoatTransform.commitAcceptedBoatTickState(player, result);
        } else if (riding != null && (riding.type == EntityTypeIds.PIG || riding.type == EntityTypeIds.STRIDER)) {
            // Keep the root's Entity#deltaMovement in sync when later passive ticks or block-entity moves read it.
            Vec3 velocity = uniqueVelocity(player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            if (velocity != null) riding.deltaMovement = velocity;
        } else if (riding instanceof PacketEntityHappyGhast) {
            Vec3 velocity =
                    uniqueVelocity(player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            if (velocity != null) {
                riding.deltaMovement = velocity;
            }
        } else if (riding instanceof PacketEntityNautilus nautilus) {
            Vec3 velocity =
                    uniqueVelocity(player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            if (velocity != null) {
                riding.deltaMovement = velocity;
            }
            if (nautilus.pendingJumpScale > 0.0F) {
                nautilus.pendingJumpScale = 0.0F;
                nautilus.executeRidersJump();
            }
        }

        if (riding instanceof PacketEntityCamel camel && camel.actionAlive) {
            EntityPose pose = camel.actionPose;
            // Camel#tickRidden stood the camel up before the travel, which the prediction moved standing.
            if (result.getSimulationContext().isRiddenStandUp()) {
                camel.standUp(player.compensatedWorld.clientGameTime());
            }
            // Camel#tick then stands a sitting camel up at once in water; Entity#baseTick set isInWater at the start.
            if (camel.isCamelSitting()
                    && result.getSimulationContext().getWorldData().isVehicleInWater()) {
                camel.standUpInstantly(player.compensatedWorld.clientGameTime());
            }
            // Entity#setPose refreshes the dimensions at once.
            if (camel.actionPose != pose) {
                camel.refreshDimensions(player);
                player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            }
        }

        // For performance reasons - and to make debugging less painful - don't try spamming jumps
        PacketEntityHorse horse = player.compensatedEntities.vehicles.getClientVisibleHorseRoot();
        if (horse != null) {
            boolean consumedHorseJump = horse.horseJump > 0.0D
                    && result.getSimulationContext().getLastOnGround().determinePessimistically();
            if (consumedHorseJump) {
                // MCP-Reborn AbstractHorse#tickRidden clears
                // playerJumpPendingScale on every grounded ridden horse tick,
                // regardless of whether executeRidersJump runs or the horse is
                // already jumping. So a client-grounded tick provably consumes
                // the currently active pending jump scale.
                horse.horseJump = 0.0D;
                if (horse instanceof PacketEntityCamel camel) {
                    camel.dashCooldown = 55;
                    camel.dashing = true;
                }
            }
        }
    }

    /**
     * Through 1.21.3 ServerboundMoveVehiclePacket carries no ground, so the root keeps the one its own move set. From
     * 1.21.4 handleMoveVehicle applies the packet's ground, so where the packet carries one it is staged to match.
     */
    private Boolean unreportedRootGround(PredictionResult result) {
        if (result.isExempt() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return null;
        Boolean ground = JavaRootGround.after(player, result);
        if (ground != null) player.packetStateData.stageDesiredOnGround(ground);
        return ground;
    }

    void evaluateItemControlledVehicle(PacketEntity riding) {
        // VehicleC is a Java-only check. This direct callback bypasses the
        // CheckManager dispatch routes, so preserve their Bedrock support gate
        // explicitly here.
        if (player.isBedrockMovement()) {
            return;
        }

        ac.cult.blocksim.data.ItemDefinition requiredItem;
        if (riding != null && riding.type == EntityTypeIds.PIG) {
            requiredItem = ac.cult.cultac.utils.inventory.ItemTypes.CARROT_ON_A_STICK;
        } else if (riding != null && riding.type == EntityTypeIds.STRIDER) {
            requiredItem = ac.cult.cultac.utils.inventory.ItemTypes.WARPED_FUNGUS_ON_A_STICK;
        } else {
            return;
        }

        VehicleC vehicleC = player.checkManager.getCheck(VehicleC.class);
        // mojang is stupid and sends held item a tick behind
        // TODO: just buffer this?
        if (player.getInventory().hasClientSelectableHandItem(requiredItem)) {
            vehicleC.reward();
        } else {
            vehicleC.flag();
        }
    }

    private void promoteInputAfterClientTickEnd() {
        PacketEntity root = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        if (!usesStagedVehicleInput(root)) {
            return;
        }

        // ClientLevel ticks the local-authoritative root before LocalPlayer#rideTick.
        // LocalPlayer#tick sends changed input, Rot, and MoveVehicle from inside
        // that rideTick, and Minecraft sends ClientTickEnd only after level ticking
        // has finished. Tick-end is therefore the first packet proving the latest
        // raw input has been copied into the controlled root for the next root tick.
        // A boat's keys are its own PacketEntity#boatInput (BoatData#onClientTickEnd).
        player.boatData.useNextInputForVehicleTick();
    }

    public static boolean usesStagedVehicleInput(PacketEntity root) {
        return root != null
                && (EntityTypeUtil.isBoat(root.type)
                        || EntityTypeUtil.isHorseFamily(root.type)
                        || root.type == EntityTypeIds.PIG
                        || root.type == EntityTypeIds.STRIDER
                        || root instanceof PacketEntityHappyGhast
                        || root instanceof PacketEntityNautilus);
    }

    private Vec3 uniqueVelocity(Set<Vec3> velocities) {
        Vec3 unique = null;
        for (Vec3 velocity : velocities) {
            if (unique == null) {
                unique = velocity;
            } else if (unique.distanceToSqr(velocity) > 1.0E-12D) {
                return null;
            }
        }
        return unique;
    }
}
