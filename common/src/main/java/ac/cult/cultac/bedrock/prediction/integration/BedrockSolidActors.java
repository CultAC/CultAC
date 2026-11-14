package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The solid actors a Bedrock move collides with after the blocks: every actor with the collidable flag whose box
 * meets the mover's box grown by {@link #REACH}, at the box its last lerp step left it, with the depenetration limit
 * it gives the move. There is no push between actors. Tracked entities come first in tracker order, then the actors
 * only the client holds in spawn order; the move meets them in the reverse of that order. Cult's box for a moving
 * actor can stand off the client's: a lerp step for a 1.26.60 or later client, anywhere its history delay allows for an
 * earlier one. Each actor carries how far that may be, and the offsets where the client may show it instead.
 */
public final class BedrockSolidActors {
    private static final double REACH = 2.0D;

    private BedrockSolidActors() {}

    public static List<HardCollidingEntityCollision> collect(CultPlayer player, SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        // Only a mob collides with solid actors: the player on foot, or the mob it rides, but not a boat.
        if (vehicle != null && !vehicle.isLivingEntity()) {
            player.bedrockState.movementCorrections.solidActors(-1, null);
            return List.of();
        }
        List<Nearby> nearby = new ArrayList<>();
        player.bedrockState.movementCorrections.solidActors(
                context.getBedrockInput() == null ? -1 : context.getBedrockInput().getClientTick(), nearby);
        SimpleCollisionBox reach = context.getFromMaximumExtent().expand(REACH);
        List<HardCollidingEntityCollision> solid = new ArrayList<>();
        CompensatedEntities entities = context.getEntities();
        PacketEntity rootOfSelf = entities.rootOfSelf();
        SimpleCollisionBox box = new SimpleCollisionBox();
        // A client before 1.26.60 shows each actor somewhere in the positions it was sent within its history delay.
        boolean legacy = context.getBedrockInput() != null
                && player.bedrockState.getProtocolVersion().isKnown()
                && player.bedrockState.getProtocolVersion().compareTo(BedrockProtocolVersion.V1_26_60) < 0;
        for (PacketEntity entity : entities.entityMap.values()) {
            // A death event leaves Bedrock's solid box present until its flag clears or the actor is removed.
            if (!entity.bedrockCollidable) continue;
            if (entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            if (!entity.bedrockShownBox(player, box).isIntersected(reach)) continue;
            float actorBuffer = 0.0F;
            List<ac.cult.cultac.utils.math.Vec3> elsewhere = List.of();
            // A rider is seated from the vehicle its chain ends at, a client tick behind it for each seat between
            // them, so it may stand off Cult's box wherever that vehicle may.
            PacketEntity carrier = entity;
            int seats = 0;
            if (entity.getRiding() != null && entities.isUntrackedRider(entity)) {
                while (carrier.getRiding() != null && seats < entities.entityMap.size()) {
                    carrier = carrier.getRiding();
                    seats++;
                }
            }
            var shown = carrier.bedrockTickStart(seats);
            if (!legacy && context.getBedrockInput() != null) {
                var envelope = player.bedrockState.solidActorBuffer.steps;
                // The rider's next step is the one its vehicle took a tick before.
                actorBuffer = envelope.buffer(
                        entity.getEntityId(),
                        context.getBedrockInput().getClientTick(),
                        shown,
                        seats == 0 ? carrier.desyncClientPos : carrier.bedrockTickStart(seats - 1),
                        seats != 0
                                ? 1
                                : carrier.newPacketLocation == null ? 0 : carrier.newPacketLocation.bedrockStepsLeft());
                if (actorBuffer > 0.0F) {
                    elsewhere = envelope.alternatives(entity.getEntityId());
                    touch(player, context, box, actorBuffer, depenetrationLimit(entity));
                }
            } else if (legacy) {
                var envelope = player.bedrockState.solidActorBuffer.legacy;
                long tick = context.getBedrockInput().getClientTick();
                actorBuffer = envelope.buffer(entity.getEntityId(), tick, carrier.desyncClientPos, shown);
                if (actorBuffer > 0.0F) elsewhere = envelope.alternatives(entity.getEntityId(), shown);
                // Such a client also pushes a player out of an actor it overlaps, however still the actor is.
                touch(player, context, box, Math.max(actorBuffer, overlapPush(player, box, context)), depenetrationLimit(entity));
            }
            var collision = new HardCollidingEntityCollision(box.copy(), depenetrationLimit(entity), actorBuffer, elsewhere);
            solid.add(collision);
            nearby.add(new Nearby(entity, NO_CLIENT_ACTOR, collision));
        }
        player.bedrockState.clientActors.colliders(reach, (runtimeId, actor, placed, sent, shown, stepsLeft) -> {
            float actorBuffer = 0.0F;
            List<ac.cult.cultac.utils.math.Vec3> elsewhere = List.of();
            if (!legacy && context.getBedrockInput() != null) {
                var envelope = player.bedrockState.solidActorBuffer.stepsClientOnly;
                int key = (int) (runtimeId ^ (runtimeId >>> 32));
                actorBuffer = envelope.buffer(key, context.getBedrockInput().getClientTick(), shown, sent, stepsLeft);
                if (actorBuffer > 0.0F) {
                    elsewhere = envelope.alternatives(key);
                    touch(player, context, actor, actorBuffer, BedrockDepenetrationLimit.SOLID_ACTOR);
                }
            } else if (legacy) {
                var envelope = player.bedrockState.solidActorBuffer.legacyClientOnly;
                int key = (int) (runtimeId ^ (runtimeId >>> 32));
                actorBuffer = envelope.buffer(key, context.getBedrockInput().getClientTick(), placed, sent, shown);
                if (actorBuffer > 0.0F) elsewhere = envelope.alternatives(key, shown);
                touch(
                        player,
                        context,
                        actor,
                        Math.max(actorBuffer, overlapPush(player, actor, context)),
                        BedrockDepenetrationLimit.SOLID_ACTOR);
            }
            var collision = new HardCollidingEntityCollision(
                    actor, BedrockDepenetrationLimit.SOLID_ACTOR, actorBuffer, elsewhere);
            solid.add(collision);
            nearby.add(new Nearby(null, runtimeId, collision));
        });
        return solid;
    }

    /**
     * A replay meets every solid actor at one box for all of its ticks, whenever they first ran: an actor still
     * interpolating at its latest target, any other at the box it shows now. An actor the last live tick met keeps
     * the box that tick saved once its interpolation ended or it was removed. This does not advance the actor's
     * ordinary interpolation or change the box the next live move meets.
     */
    static Replay replay(CultPlayer player, List<Nearby> nearby) {
        if (nearby == null) return Replay.NONE;
        var saved = new IdentityHashMap<PacketEntity, HardCollidingEntityCollision>();
        for (Nearby actor : nearby) if (actor.entity() != null) saved.put(actor.entity(), actor.collision());
        List<Nearby> actors = new ArrayList<>();
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity rootOfSelf = entities.rootOfSelf();
        SimpleCollisionBox box = new SimpleCollisionBox();
        for (PacketEntity entity : entities.entityMap.values()) {
            var met = saved.remove(entity);
            if (!entity.bedrockCollidable || entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            HardCollidingEntityCollision collision;
            if (entity.newPacketLocation != null && entity.newPacketLocation.bedrockStepsLeft() != 0) {
                var target = entity.desyncClientPos;
                collision = new HardCollidingEntityCollision(
                        GetBoundingBox.getPacketEntityBoundingBox(player, target.x, target.y, target.z, entity),
                        met == null ? depenetrationLimit(entity) : met.depenetrationLimit(),
                        0.0F);
            } else if (met != null) {
                collision = met;
            } else {
                collision = new HardCollidingEntityCollision(
                        entity.bedrockShownBox(player, box).copy(), depenetrationLimit(entity), 0.0F);
            }
            actors.add(new Nearby(entity, NO_CLIENT_ACTOR, collision));
        }
        // Removed since the last live tick.
        saved.forEach((entity, collision) -> actors.add(new Nearby(entity, NO_CLIENT_ACTOR, collision)));
        return new Replay(player, actors);
    }

    /**
     * The solid actors of one replay. Each replayed tick meets the actors its own live tick met ({@link Met}), at
     * their replay boxes; a tick recorded without them meets those within {@link #REACH} of its replayed start.
     */
    record Replay(CultPlayer player, List<Nearby> actors) {
        static final Replay NONE = new Replay(null, List.of());

        List<HardCollidingEntityCollision> around(SimpleCollisionBox start) {
            if (player == null) return List.of();
            SimpleCollisionBox reach = start.expand(REACH);
            List<HardCollidingEntityCollision> met = new ArrayList<>();
            for (var actor : actors) if (actor.collision().box().isIntersected(reach)) met.add(actor.collision());
            player.bedrockState.clientActors.colliders(
                    reach,
                    (SimpleCollisionBox actor) -> met.add(
                            new HardCollidingEntityCollision(actor, BedrockDepenetrationLimit.SOLID_ACTOR, 0.0F)));
            return met;
        }

        List<HardCollidingEntityCollision> met(Met live) {
            if (player == null || live.isEmpty()) return List.of();
            List<HardCollidingEntityCollision> met = new ArrayList<>();
            for (var actor : actors) if (live.entities().contains(actor.entity())) met.add(actor.collision());
            for (long runtimeId : live.clientActors()) {
                SimpleCollisionBox box = player.bedrockState.clientActors.collider(runtimeId);
                if (box != null)
                    met.add(new HardCollidingEntityCollision(box, BedrockDepenetrationLimit.SOLID_ACTOR, 0.0F));
            }
            return met;
        }
    }

    record Nearby(PacketEntity entity, long clientActor, HardCollidingEntityCollision collision) {}

    private static final long NO_CLIENT_ACTOR = Long.MIN_VALUE;

    /** The solid actors one live tick met, by identity, in the order it met them. */
    record Met(Set<PacketEntity> entities, long[] clientActors) {
        static final Met NONE = new Met(Set.of(), new long[0]);

        static Met of(List<Nearby> nearby) {
            if (nearby == null || nearby.isEmpty()) return NONE;
            Set<PacketEntity> entities = Collections.newSetFromMap(new IdentityHashMap<>());
            long[] clientActors = new long[nearby.size()];
            int count = 0;
            for (Nearby actor : nearby) {
                if (actor.entity() != null) entities.add(actor.entity());
                else clientActors[count++] = actor.clientActor();
            }
            return new Met(entities, Arrays.copyOf(clientActors, count));
        }

        boolean isEmpty() {
            return entities.isEmpty() && clientActors.length == 0;
        }
    }

    /**
     * A solid actor's push for an older client: its whole depenetration limit when its box overlaps the player where the
     * move starts.
     */
    private static float overlapPush(CultPlayer player, SimpleCollisionBox box, SimulationContext context) {
        var start = context.getStart();
        if (start == null) return 0.0F;
        return box.isIntersected(GetBoundingBox.getPlayerBoundingBox(player, start.x, start.y, start.z))
                ? BedrockDepenetrationLimit.SOLID_ACTOR.x()
                : 0.0F;
    }

    /** An actor that may stand {@code buffer} from {@code box} may meet the player's move. */
    private static void touch(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox box,
            float buffer,
            BedrockDepenetrationLimit limit) {
        SimpleCollisionBox mover = context.getFromMaximumExtent();
        if (buffer <= 0.0F || !box.copy().expand(buffer).isIntersected(mover)) return;
        player.bedrockState.solidActorBuffer.touchedBy(
                context.getBedrockInput().getClientTick(),
                buffer,
                limit,
                (box.minX + box.maxX) / 2.0D,
                (box.minZ + box.maxZ) / 2.0D,
                (mover.minX + mover.maxX) / 2.0D,
                (mover.minZ + mover.maxZ) / 2.0D);
    }


    /** How deep a move that collects {@code entity} may overlap a shape and still leave it. */
    private static BedrockDepenetrationLimit depenetrationLimit(PacketEntity entity) {
        if (entity instanceof PacketEntityShulker shulker) return shulker.bedrockDepenetrationLimit();
        return entity.isHappyGhast() ? BedrockDepenetrationLimit.HAPPY_GHAST : BedrockDepenetrationLimit.SOLID_ACTOR;
    }
}
