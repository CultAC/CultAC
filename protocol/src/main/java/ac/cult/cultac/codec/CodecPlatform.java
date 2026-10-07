package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.configuration.AbstractViaConfig;
import ac.cult.shaded.vialib.platform.UserConnectionViaVersionPlatform;
import java.io.File;
import java.util.logging.Logger;

/** Private codec configuration; no proxy injection, online-player registry or update jobs. */
final class CodecPlatform extends UserConnectionViaVersionPlatform {
    CodecPlatform(File directory) {
        super(directory);
    }

    @Override
    public String getPlatformName() {
        return "CultAC packet model";
    }

    @Override
    public String getPlatformVersion() {
        return "1";
    }

    @Override
    public Logger createLogger(String name) {
        return Logger.getLogger("CultAC-codecs");
    }

    @Override
    protected AbstractViaConfig createConfig() {
        return new AbstractViaConfig(null, Logger.getLogger("CultAC-codecs")) {
            @Override
            public void reload() {}

            @Override
            public boolean isCheckForUpdates() {
                return false;
            }
        };
    }
}
