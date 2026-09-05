package ac.cult.cultac.packet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link PacketListener} method as a packet handler.
 *
 * <p>Handlers with a lower priority run before handlers with a higher priority.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PacketHandler {
    int priority() default PacketApi.DEFAULT_HANDLER_PRIORITY;
}
