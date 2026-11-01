package ac.cult.cultac.platform.api.permissions;

/**
 * Represents the default value for a permission, used across platforms.
 * This enum provides a cross-platform abstraction for permission defaults,
 * mapping to platform-specific values (e.g., Bukkit's PermissionDefault).
 */
public enum PermissionDefaultValue {
    /**
     * Permission defaults to true (granted by default).
     * - Bukkit: Maps to PermissionDefault.TRUE
     */
    TRUE,

    /**
     * Permission defaults to false (denied by default).
     * - Bukkit: Maps to PermissionDefault.FALSE
     */
    FALSE,

    /**
     * Permission defaults to requiring operator (op) status.
     * - Bukkit: Maps to PermissionDefault.OP
     */
    OP,

    /**
     * Permission defaults to not requiring operator (op) status.
     * - Bukkit: Maps to PermissionDefault.NOT_OP
     */
    NOT_OP
}
