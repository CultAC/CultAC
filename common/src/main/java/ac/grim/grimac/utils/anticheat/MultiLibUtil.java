package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.utils.reflection.ReflectionUtils;
import net.minecraft.SharedConstants;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

public class MultiLibUtil {

    public final static Method externalPlayerMethod = ReflectionUtils.getMethod(Player.class, "isExternalPlayer");

    private static final boolean IS_PRE_1_18 = SharedConstants.getProtocolVersion() < 757;

    // TODO: cache external players for better performance, but this only matters for people using multi-lib
    public static boolean isExternalPlayer(Player player) {
        if (externalPlayerMethod == null || IS_PRE_1_18) return false;
        try {
            return (boolean) externalPlayerMethod.invoke(player);
        } catch (Exception e) {
            LogUtil.error("Failed to invoke external player method", e);
            return false;
        }
    }
}
