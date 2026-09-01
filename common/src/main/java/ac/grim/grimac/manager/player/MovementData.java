package ac.grim.grimac.manager.player;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import lombok.Getter;
import lombok.RequiredArgsConstructor;


@RequiredArgsConstructor
public class MovementData {

    private final GrimPlayer player;

    @Getter private double x, y, z, lastX, lastY, lastZ,
            deltaX, deltaY, deltaZ,
            lastDeltaX, lastDeltaY, lastDeltaZ,
            deltaXZ, lastDeltaXZ;

    @Getter private boolean mathematicallyOnGround;

    public void handle(double newX, double newY, double newZ) {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        //
        this.lastDeltaX = this.deltaX;
        this.lastDeltaY = this.deltaY;
        this.lastDeltaZ = this.deltaZ;
        this.lastDeltaXZ = this.deltaXZ;
        //
        this.deltaX = this.x - this.lastX;
        this.deltaY = this.y - this.lastY;
        this.deltaZ = this.z - this.lastZ;
        this.deltaXZ = GrimMath.magnitude(this.deltaX, this.deltaZ);
        //
        this.mathematicallyOnGround = newY % 0.015625 == 0.0;
    }

}
