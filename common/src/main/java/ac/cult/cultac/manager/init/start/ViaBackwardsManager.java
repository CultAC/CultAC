package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;

public class ViaBackwardsManager implements StartableInitable {
    @Override
    public void start() {
        System.setProperty("com.viaversion.handlePingsAsInvAcknowledgements", "true");
        ViaClientBlockShapeMappings.initialize();
    }
}
