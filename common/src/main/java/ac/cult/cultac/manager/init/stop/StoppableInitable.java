package ac.cult.cultac.manager.init.stop;

import ac.cult.cultac.manager.init.Initable;

public interface StoppableInitable extends Initable {
    void stop();
}
