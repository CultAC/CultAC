package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import java.util.List;
import java.util.Set;
import lombok.Data;

@Data
public class PistonPushes {
    SimpleCollisionBox push;
    SimpleCollisionBox pistonPush;
    SimpleCollisionBox shulkerPush;
    Set<Direction> slimeBlockLaunches;
    // Zero and each running sum, in Level#tickBlockEntities order, of the shulker box block shoves this packet carries.
    SimpleCollisionBox shulkerBoxPush = new SimpleCollisionBox();
    // Whether this packet's shulker box block shoves move the player before its travel, rather than after it.
    boolean shulkerBoxShoveBeforeTravel;
    // What the shoves before the travel leave the player, each from where the last left it; set when one reaches the
    // player and they are the only Entity#move calls besides the travels of this packet and the last.
    ShulkerBoxShoveResult shulkerBoxShoveResult;
    // The shoves after the travel, in Level#tickBlockEntities order; set when they are the only Entity#move calls
    // besides the travels of this packet and the last.
    List<CompensatedBlockEntities.ShulkerShove> shulkerBoxShovesAfterTravel;

    public PistonPushes(SimpleCollisionBox push, Set<Direction> slimeBlockLaunches) {
        this(push, push.copy(), new SimpleCollisionBox(), slimeBlockLaunches);
    }

    public PistonPushes(
            SimpleCollisionBox push,
            SimpleCollisionBox pistonPush,
            SimpleCollisionBox shulkerPush,
            Set<Direction> slimeBlockLaunches) {
        this.push = push;
        this.pistonPush = pistonPush;
        this.shulkerPush = shulkerPush;
        this.slimeBlockLaunches = slimeBlockLaunches;
    }

    /**
     * Entity#onGround as the travel starts after the shulker box block shoves, which the local player's Entity#move
     * sets from its vertical collision below; null unless {@link #shulkerBoxShoveResult} is set.
     */
    public Boolean onGroundAfterShoveBeforeTravel() {
        return shulkerBoxShoveResult == null ? null : shulkerBoxShoveResult.onGround();
    }
}
