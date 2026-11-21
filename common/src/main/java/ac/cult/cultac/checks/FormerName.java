package ac.cult.cultac.checks;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A name a check had before it was renamed; see {@link CheckNames}. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface FormerName {
    String name();

    /** The last name version the check had this name in. */
    int until();
}
