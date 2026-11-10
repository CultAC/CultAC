package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;

/** Reusable positions on both sides of Entity#rideTick; candidates come only from the compensated parent. */
final class ClientRiderPosition {
    final SimpleCollisionBox current = new SimpleCollisionBox();
    final SimpleCollisionBox before = new SimpleCollisionBox();
    final SimpleCollisionBox point = new SimpleCollisionBox();
    private ArrayList<SimpleCollisionBox> extraCurrent;
    private ArrayList<SimpleCollisionBox> extraBefore;
    int currentCount = 1;
    int beforeCount = 1;
    boolean positioned;
    int tickedAt = Integer.MIN_VALUE;
    int seatedAtPass = Integer.MIN_VALUE;

    SimpleCollisionBox box(boolean previous, int index) {
        if (index == 0) return previous ? before : current;
        return (previous ? extraBefore : extraCurrent).get(index - 1);
    }

    void put(boolean previous, int index, SimpleCollisionBox position) {
        if (index == 0) {
            (previous ? before : current).set(position);
            return;
        }
        ArrayList<SimpleCollisionBox> boxes = previous ? extraBefore : extraCurrent;
        if (boxes == null) {
            boxes = new ArrayList<>(2);
            if (previous) extraBefore = boxes;
            else extraCurrent = boxes;
        }
        while (boxes.size() < index) boxes.add(new SimpleCollisionBox());
        boxes.get(index - 1).set(position);
    }
}
