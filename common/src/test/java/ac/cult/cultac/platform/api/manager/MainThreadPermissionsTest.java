package ac.cult.cultac.platform.api.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.platform.api.permissions.PermissionDefaultValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MainThreadPermissionsTest {
    private final List<String> registered = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final AtomicBoolean mainThread = new AtomicBoolean();
    private final MainThreadPermissions permissions =
            new MainThreadPermissions((name, value) -> registered.add(name + "=" + value), mainThread::get, tasks::add);

    @Test
    void registrationOffTheMainThreadWaitsForOneMainThreadTask() {
        permissions.registerPermission("cult.exempt.a", PermissionDefaultValue.FALSE);
        permissions.registerPermission("cult.exempt.b", PermissionDefaultValue.TRUE);
        assertEquals(List.of(), registered);
        assertEquals(1, tasks.size());
        mainThread.set(true);
        tasks.removeFirst().run();
        assertEquals(List.of("cult.exempt.a=FALSE", "cult.exempt.b=TRUE"), registered);
    }

    @Test
    void registrationOnTheMainThreadKeepsOrderBehindPendingOnes() {
        permissions.registerPermission("cult.exempt.a", PermissionDefaultValue.FALSE);
        mainThread.set(true);
        permissions.registerPermission("cult.exempt.b", PermissionDefaultValue.FALSE);
        assertEquals(List.of(), registered);
        tasks.removeFirst().run();
        permissions.registerPermission("cult.exempt.c", PermissionDefaultValue.FALSE);
        assertEquals(List.of("cult.exempt.a=FALSE", "cult.exempt.b=FALSE", "cult.exempt.c=FALSE"), registered);
        assertEquals(List.of(), tasks);
    }
}
