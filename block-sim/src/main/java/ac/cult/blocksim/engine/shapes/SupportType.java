package ac.cult.blocksim.engine.shapes;

import ac.cult.blocksim.engine.Direction;

/** Support shapes are compared in face-shape coordinates, exactly as in vanilla. */
public enum SupportType {
    FULL(Shapes.block()),
    CENTER(Shapes.create(7.0 / 16.0, 0.0, 7.0 / 16.0, 9.0 / 16.0, 10.0 / 16.0, 9.0 / 16.0)),
    RIGID(Shapes.join(Shapes.block(), Shapes.create(2.0 / 16.0, 0.0, 2.0 / 16.0, 14.0 / 16.0, 1.0, 14.0 / 16.0), BooleanOp.ONLY_FIRST));

    private final VoxelShape required;
    SupportType(VoxelShape required) { this.required = required; }

    public boolean supports(VoxelShape support, Direction direction) {
        return switch (this) { case FULL -> full(support, direction); case CENTER -> center(support, direction); case RIGID -> rigid(support, direction); };
    }

    private boolean full(VoxelShape support, Direction direction) {
        return !Shapes.joinIsNotEmpty(Shapes.block(), support.face(direction), BooleanOp.NOT_SAME);
    }

    private boolean center(VoxelShape support, Direction direction) {
        return !Shapes.joinIsNotEmpty(support.face(direction), required, BooleanOp.ONLY_SECOND);
    }

    private boolean rigid(VoxelShape support, Direction direction) {
        return !Shapes.joinIsNotEmpty(support.face(direction), required, BooleanOp.ONLY_SECOND);
    }
}
