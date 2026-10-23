package ac.cult.cultac.utils.math;

import ac.cult.cultac.platform.api.world.PlatformWorld;
import java.lang.ref.WeakReference;
import java.util.Objects;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Location implements Cloneable {
    private @Nullable WeakReference<PlatformWorld> world;

    @Getter
    private double x;

    @Getter
    private double y;

    @Getter
    private double z;

    @Getter
    private float pitch;

    @Getter
    private float yaw;

    public Location(PlatformWorld world, double x, double y, double z) {
        this(world, x, y, z, 0.0F, 0.0F);
    }

    public Location(PlatformWorld world, double x, double y, double z, float yaw, float pitch) {
        if (world != null) {
            this.world = new WeakReference<>(world);
        }

        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.yaw = yaw;
    }

    public PlatformWorld getWorld() {
        if (this.world == null) {
            return null;
        } else {
            return this.world.get();
        }
    }

    public void setWorld(@Nullable PlatformWorld world) {
        this.world = world == null ? null : new WeakReference<>(world);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        } else {
            Location other = (Location) obj;
            return Objects.equals(
                            this.world == null ? null : this.world.get(),
                            other.world == null ? null : other.world.get())
                    && Double.doubleToLongBits(this.x) == Double.doubleToLongBits(other.x)
                    && Double.doubleToLongBits(this.y) == Double.doubleToLongBits(other.y)
                    && Double.doubleToLongBits(this.z) == Double.doubleToLongBits(other.z)
                    && Float.floatToIntBits(this.pitch) == Float.floatToIntBits(other.pitch)
                    && Float.floatToIntBits(this.yaw) == Float.floatToIntBits(other.yaw);
        }
    }

    @Override
    public int hashCode() {
        int hash = 3;
        PlatformWorld world = this.world == null ? null : this.world.get();
        hash = 19 * hash + (world != null ? world.hashCode() : 0);
        hash = 19 * hash + Long.hashCode(Double.doubleToLongBits(this.x));
        hash = 19 * hash + Long.hashCode(Double.doubleToLongBits(this.y));
        hash = 19 * hash + Long.hashCode(Double.doubleToLongBits(this.z));
        hash = 19 * hash + Float.floatToIntBits(this.pitch);
        hash = 19 * hash + Float.floatToIntBits(this.yaw);
        return hash;
    }

    @Override
    public String toString() {
        return "Location{world=" + (this.world == null ? null : this.world.get()) + ",x=" + this.x + ",y=" + this.y
                + ",z=" + this.z + ",pitch=" + this.pitch + ",yaw=" + this.yaw + "}";
    }

    public @NotNull Location clone() {
        try {
            return (Location) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new Error(e);
        }
    }

    public int getBlockX() {
        return CultMath.mojangFloor(x);
    }

    public int getBlockY() {
        return CultMath.mojangFloor(y);
    }

    public int getBlockZ() {
        return CultMath.mojangFloor(z);
    }

    public boolean isWorldLoaded() {
        if (this.world == null) {
            return false;
        } else {
            PlatformWorld world = this.world.get();
            return world != null && world.isLoaded();
        }
    }
}
