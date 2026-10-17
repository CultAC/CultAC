package ac.cult.cultac.platform.bukkit.entity;

import ac.cult.cultac.platform.api.entity.CultEntity;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.platform.bukkit.utils.convert.BukkitConversionUtils;
import ac.cult.cultac.platform.bukkit.world.BukkitPlatformWorld;
import ac.cult.cultac.utils.math.Location;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

public class BukkitCultEntity implements CultEntity {

    private final Entity entity;
    private BukkitPlatformWorld bukkitPlatformWorld;

    public BukkitCultEntity(Entity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    public Entity getBukkitEntity() {
        return this.entity;
    }

    @Override
    public UUID getUniqueId() {
        return entity.getUniqueId();
    }

    @Override
    public boolean eject() {
        return entity.eject();
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location) {
        org.bukkit.Location bLoc = BukkitConversionUtils.toBukkitLocation(location);
        return this.entity.teleportAsync(bLoc);
    }

    @Override
    @NotNull
    public Entity getNative() {
        return entity;
    }

    @Override
    public boolean isDead() {
        return this.entity.isDead();
    }

    // TODO replace with PlayerWorldChangeEvent listener instead of checking for equality for better performance
    @Override
    public PlatformWorld getWorld() {
        if (bukkitPlatformWorld == null || !bukkitPlatformWorld.bukkitWorld().equals(entity.getWorld())) {
            bukkitPlatformWorld = new BukkitPlatformWorld(entity.getWorld());
        }

        return bukkitPlatformWorld;
    }

    @Override
    public Location getLocation() {
        org.bukkit.Location location = this.entity.getLocation();
        return new Location(
                this.getWorld(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    @Override
    public double distanceSquared(double oX, double oY, double oZ) {
        double x = this.entity.getX();
        double y = this.entity.getY();
        double z = this.entity.getZ();
        double distX = (x - oX) * (x - oX);
        double distY = (y - oY) * (y - oY);
        double distZ = (z - oZ) * (z - oZ);
        return distX + distY + distZ;
    }
}
