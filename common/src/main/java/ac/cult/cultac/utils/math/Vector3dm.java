package ac.cult.cultac.utils.math;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class Vector3dm implements Cloneable, Serializable {
    @Serial
    private static final long serialVersionUID = -2657651106777219169L;

    @Getter
    protected double x;

    @Getter
    protected double y;

    @Getter
    protected double z;

    @Contract(pure = true)
    public Vector3dm() {
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
    }

    @Contract(pure = true)
    public Vector3dm(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Contract(pure = true)
    public Vector3dm(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Contract(pure = true)
    public Vector3dm(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public @NotNull Vector3dm add(@NotNull Vector3dm vec) {
        return add(vec.x, vec.y, vec.z);
    }

    public @NotNull Vector3dm add(double x, double y, double z) {
        this.x += x;
        this.y += y;
        this.z += z;
        return this;
    }

    public @NotNull Vector3dm subtract(@NotNull Vector3dm vec) {
        return subtract(vec.x, vec.y, vec.z);
    }

    public @NotNull Vector3dm subtract(double x, double y, double z) {
        this.x -= x;
        this.y -= y;
        this.z -= z;
        return this;
    }

    public double length() {
        return Math.sqrt(CultMath.square(this.x) + CultMath.square(this.y) + CultMath.square(this.z));
    }

    public double lengthSquared() {
        return CultMath.square(this.x) + CultMath.square(this.y) + CultMath.square(this.z);
    }

    public double distance(@NotNull Vector3dm o) {
        return Math.sqrt(distanceSquared(o));
    }

    public double distanceSquared(@NotNull Vector3dm o) {
        return distanceSquared(o.x, o.y, o.z);
    }

    public double distanceSquared(double oX, double oY, double oZ) {
        return CultMath.square(this.x - oX) + CultMath.square(this.y - oY) + CultMath.square(this.z - oZ);
    }

    public @NotNull Vector3dm multiply(double m) {
        this.x *= m;
        this.y *= m;
        this.z *= m;
        return this;
    }

    public @NotNull Vector3dm setX(double x) {
        this.x = x;
        return this;
    }

    public @NotNull Vector3dm setY(double y) {
        this.y = y;
        return this;
    }

    public @NotNull Vector3dm setZ(double z) {
        this.z = z;
        return this;
    }

    @Contract(value = "null -> false", pure = true)
    @Override
    public boolean equals(Object obj) {
        return obj instanceof Vector3dm other
                && Math.abs(this.x - other.x) < 1.0E-6
                && Math.abs(this.y - other.y) < 1.0E-6
                && Math.abs(this.z - other.z) < 1.0E-6
                && this.getClass().equals(obj.getClass());
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 79 * hash + Long.hashCode(Double.doubleToLongBits(this.x));
        hash = 79 * hash + Long.hashCode(Double.doubleToLongBits(this.y));
        hash = 79 * hash + Long.hashCode(Double.doubleToLongBits(this.z));
        return hash;
    }

    public @NotNull Vector3dm clone() {
        try {
            return (Vector3dm) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new Error(e);
        }
    }

    @Override
    public String toString() {
        return this.x + "," + this.y + "," + this.z;
    }
}
