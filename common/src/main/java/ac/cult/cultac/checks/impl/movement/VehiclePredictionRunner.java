package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.BoatTransform;
import ac.cult.cultac.checks.impl.vehicle.VehicleC;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.VehicleListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.data.SprintingState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;

import java.util.Set;

public class VehiclePredictionRunner extends CultProcessor implements VehicleListener, ClientTickEndListener {
    public VehiclePredictionRunner(CultPlayer playerData) {
        super(playerData);
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        if (player.vehicleData.camelSprintingState == SprintingState.STOPPING) {
            player.vehicleData.camelSprintingState = SprintingState.STOPPED;
        } else if (player.vehicleData.camelSprintingState == SprintingState.STOPPED && player.isSprinting) {
            player.vehicleData.camelSprintingState = SprintingState.STARTED;
        }
        PacketEntity root = player.compensatedEntities.vehicles.getVelocityMovementVehicle();
        if (root instanceof PacketEntityNautilus nautilus) {
            nautilus.tickClientState();
        }
        if (root instanceof PacketEntityCamel camel) {
            camel.tickDashCooldown();
        }
        PacketEntityHorse horse = player.compensatedEntities.vehicles.getClientVisibleHorseRoot();
        if (horse != null) {
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
        }

        promoteInputAfterClientTickEnd();
    }

    @Override
    public void process(final VehiclePositionUpdate vehicleUpdate) {
        // We previously didn't do vehicle setbacks because vehicle netcode sucks
        // But the only solution is to fix mojang's bugs, as otherwise bypasses and falses will occur
        PredictionResult result = player.checkManager.getSimulationProcessor().onVehiclePositionUpdate(vehicleUpdate);

        if (result == null) {
            return;
        }
        boolean vehicleMovementFromClientTick = player.packetStateData.isVehicleMovementFromClientTick();
        PacketEntity riding = result.getSimulationContext().getVehicle();
        if (riding instanceof PacketEntityCamel camel) {
            camel.lastPredictedInLiquid = result.getSimulationContext().getWorldData().getInWater().determineOptimistically()
                    || result.getSimulationContext().getWorldData().getInLava().determineOptimistically();
        }

        if (vehicleUpdate.getTeleportAcceptData().isTeleport()) {
            player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.UNKNOWN);
            return;
        }

        if (vehicleMovementFromClientTick) {
            evaluateItemControlledVehicle(riding);
        }

        boolean isStriderInLava = riding != null
                && riding.isStrider()
                && result.getSimulationContext().getWorldData().getInLava().determineOptimistically();

        CollideAxisData.CollideResult downCollide = result.getCollideAxisData().getYNeg();
        boolean canCollideDown = downCollide != null && downCollide.isLikelyCollide();

        DesyncStatus lastOnGround = null;

        for (PredictionResult reality : result.getRealities()) {
            SimpleCollisionBox valid = reality.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
            if ((!canCollideDown && !reality.getValidMovements().isCanStep() && !isStriderInLava) || valid.maxY >= 0) {
                // Must be off of the ground
                lastOnGround = lastOnGround == null ? DesyncStatus.FALSE : lastOnGround.addBoolean(false);
            }

            // The player is off the ground and must collide onto the ground
            // Entity#move refreshes onGround after every downward collision,
            // including consecutive grounded ticks. Legacy vehicle packets have
            // no wire bit to supply that result after this collision inference.
            if ((!vehicleUpdate.isHasOnGround() || !reality.getSimulationContext().isOnGround())
                    && downCollide != null && downCollide.isLikelyCollide()
                    && valid.maxY + 0.001 < reality.getTarget().y // And their valid Y is below their actual movement
                    && downCollide.inMovementSpace(reality.getSimulationContext().getLastStuckSpeed().y) + 0.001
                            >= reality.getTarget().y) { // And they recovered by colliding
                // Must be on the ground
                lastOnGround = lastOnGround == null ? DesyncStatus.TRUE : lastOnGround.addBoolean(true);
            }
        }

        DesyncStatus nextLastOnGround = lastOnGround == null ? DesyncStatus.UNKNOWN : lastOnGround;
        if (vehicleMovementFromClientTick && riding != null && !riding.isBoat() && vehicleUpdate.isHasOnGround()) {
            // MCP-Reborn LocalPlayer#tick sends ServerboundMoveVehiclePacket.fromEntity(rootVehicle)
            // after the root tick, and ServerboundMoveVehiclePacket#fromEntity copies the
            // root entity's post-tick Entity#onGround exactly on protocol versions
            // which carry that field. The next ridden LivingEntity tick uses that
            // carried onGround state to choose grounded vs flying speed.
            nextLastOnGround = DesyncStatus.fromBoolean(vehicleUpdate.isOnGround());
        }
        player.checkManager.getSimulationProcessor().setLastOnGround(nextLastOnGround);
        if (vehicleMovementFromClientTick && riding != null && !vehicleUpdate.isHasOnGround()
                && !nextLastOnGround.isDesync()) {
            // Older MoveVehicle packets omit onGround. Carry ground transitions
            // proved by Entity#move's downward collision instead of overwriting
            // the entity with the packet accessor's absent-field default.
            riding.onGround = nextLastOnGround.determinePessimistically();
        }

        if (!vehicleMovementFromClientTick) {
            // MCP-Reborn ClientPacketListener#handleMoveVehicle emits this
            // packet from the packet handler. It does not run LocalPlayer#tick,
            // Entity#rideTick, or AbstractBoat#tick, so control input and horse
            // jump state are not consumed by this packet.
            return;
        }

        if (riding != null && riding.isBoat()) {
            // AbstractBoat stores status, waterLevel, lastYd, nullifyNextY, and
            // deltaRotation on the root entity. A packet accepted from
            // LocalPlayer#tick proves that AbstractBoat#tick completed, so carry
            // those exact post-tick fields into the next vehicle prediction.
            BoatTransform.commitAcceptedBoatTickState(player, result);
        } else if (riding instanceof PacketEntityHappyGhast) {
            Vec3 velocity = uniqueVelocity(player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            if (velocity != null) {
                riding.deltaMovement = velocity;
            }
        } else if (riding instanceof PacketEntityNautilus nautilus) {
            Vec3 velocity = uniqueVelocity(player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            if (velocity != null) {
                riding.deltaMovement = velocity;
            }
            if (nautilus.pendingJumpScale > 0.0D) {
                nautilus.pendingJumpScale = 0.0D;
                nautilus.dashCooldown = 40;
                nautilus.dashing = true;
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

    void evaluateItemControlledVehicle(PacketEntity riding) {
        // VehicleC is a Java-only check. This direct callback bypasses the
        // CheckManager dispatch routes, so preserve their Bedrock support gate
        // explicitly here.
        if (player.isBedrockMovement()) {
            return;
        }

        Material requiredItem;
        if (riding != null && riding.type == EntityTypesCompat.PIG) {
            requiredItem = Material.CARROT_ON_A_STICK;
        } else if (riding != null && riding.type == EntityTypesCompat.STRIDER) {
            requiredItem = Material.WARPED_FUNGUS_ON_A_STICK;
        } else {
            return;
        }

        VehicleC vehicleC = player.checkManager.getCheck(VehicleC.class);
        if (player.getInventory().hasClientSelectedHandItem(requiredItem)) {
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
        player.boatData.useNextInputForBoatTick();
    }

    private boolean usesStagedVehicleInput(PacketEntity root) {
        return root != null
                && (EntityTypeUtil.isBoat(root.type)
                || EntityTypeUtil.isHorseFamily(root.type)
                || root.type == EntityTypesCompat.PIG
                || root.type == EntityTypesCompat.STRIDER
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
