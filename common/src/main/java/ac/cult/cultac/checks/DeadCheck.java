package ac.cult.cultac.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a check that is provably dead on the supported 1.21.2+ matrix.
 * The class is retained deliberately (port-faithfulness / lineage documentation).
 * This annotation is informational only: registration and behavior are unchanged.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DeadCheck {
    Reason reason();

    String detail() default "";

    enum Reason {
        /** The 1.21.2+ wire protocol cannot express the trigger (encoding removed or decode-time rejected). */
        WIRE_UNTRIGGERABLE,
        /** Inert stub, never registered/constructed, or source-disabled. */
        DEAD_BY_CONSTRUCTION,
        /** The check's own version gate excludes the primary supported client target (current-version 26.x clients). */
        VERSION_GATED
    }
}
