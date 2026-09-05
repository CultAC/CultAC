package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.utils.collisions.ViaClientBlockShapeMappings;

public class ViaBackwardsManager implements StartableInitable {
    @Override
    public void start() {
        System.setProperty("com.viaversion.handlePingsAsInvAcknowledgements", "true");
        ViaClientBlockShapeMappings.initialize();
    }
}
