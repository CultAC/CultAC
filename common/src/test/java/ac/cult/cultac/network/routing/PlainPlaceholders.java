package ac.cult.cultac.network.routing;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/** Gives the offline platform loader a placeholder manager that leaves text as it is, until closed. */
public final class PlainPlaceholders implements AutoCloseable {
    private final Field loader;
    private final Object original;

    public PlainPlaceholders() throws ReflectiveOperationException {
        loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        original = loader.get(CultAPI.INSTANCE);
        MessagePlaceHolderManager placeholders = (player, text) -> text;
        loader.set(
                CultAPI.INSTANCE,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PlatformLoader.class},
                        (proxy, method, args) -> method.getName().equals("getMessagePlaceHolderManager")
                                ? placeholders
                                : method.invoke(original, args)));
    }

    @Override
    public void close() throws ReflectiveOperationException {
        loader.set(CultAPI.INSTANCE, original);
    }
}
