package net.minecraft.world.phys.shapes;

import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jspecify.annotations.Nullable;

public abstract class VoxelShape {
    protected final DiscreteVoxelShape shape;
    private @Nullable VoxelShape @Nullable [] faces;

    protected VoxelShape(DiscreteVoxelShape shape) {
        this.shape = shape;
    }

    public double min(Direction.Axis axis) {
        int i = this.shape.firstFull(axis);
        return i >= this.shape.getSize(axis) ? Double.POSITIVE_INFINITY : this.get(axis, i);
    }

    public double max(Direction.Axis axis) {
        int i = this.shape.lastFull(axis);
        return i <= 0 ? Double.NEGATIVE_INFINITY : this.get(axis, i);
    }

    public AABB bounds() {
        if (this.isEmpty()) {
            throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("No bounds for empty shape."));
        } else {
            return new AABB(
                this.min(Direction.Axis.X),
                this.min(Direction.Axis.Y),
                this.min(Direction.Axis.Z),
                this.max(Direction.Axis.X),
                this.max(Direction.Axis.Y),
                this.max(Direction.Axis.Z)
            );
        }
    }

    public VoxelShape singleEncompassing() {
        return this.isEmpty()
            ? Shapes.empty()
            : Shapes.box(
                this.min(Direction.Axis.X),
                this.min(Direction.Axis.Y),
                this.min(Direction.Axis.Z),
                this.max(Direction.Axis.X),
                this.max(Direction.Axis.Y),
                this.max(Direction.Axis.Z)
            );
    }

    protected double get(Direction.Axis axis, int index) {
        return this.getCoords(axis).getDouble(index);
    }

    public abstract DoubleList getCoords(Direction.Axis axis);

    public boolean isEmpty() {
        return this.shape.isEmpty();
    }

    public VoxelShape move(Vec3 offset) {
        return this.move(offset.x, offset.y, offset.z);
    }

    public VoxelShape move(Vec3i offset) {
        return this.move(offset.getX(), offset.getY(), offset.getZ());
    }

    public VoxelShape move(double xOffset, double yOffset, double zOffset) {
        return (VoxelShape)(this.isEmpty()
            ? Shapes.empty()
            : new ArrayVoxelShape(
                this.shape,
                new OffsetDoubleList(this.getCoords(Direction.Axis.X), xOffset),
                new OffsetDoubleList(this.getCoords(Direction.Axis.Y), yOffset),
                new OffsetDoubleList(this.getCoords(Direction.Axis.Z), zOffset)
            ));
    }

    public VoxelShape optimize() {
        VoxelShape[] voxelShapes = new VoxelShape[]{Shapes.empty()};
        this.forAllBoxes((x1, y1, z1, x2, y2, z2) -> voxelShapes[0] = Shapes.joinUnoptimized(voxelShapes[0], Shapes.box(x1, y1, z1, x2, y2, z2), BooleanOp.OR));
        return voxelShapes[0];
    }

    public void forAllEdges(Shapes.DoubleLineConsumer action) {
        this.shape
            .forAllEdges(
                (x1, y1, z1, x2, y2, z2) -> action.consume(
                    this.get(Direction.Axis.X, x1),
                    this.get(Direction.Axis.Y, y1),
                    this.get(Direction.Axis.Z, z1),
                    this.get(Direction.Axis.X, x2),
                    this.get(Direction.Axis.Y, y2),
                    this.get(Direction.Axis.Z, z2)
                ),
                true
            );
    }

    public void forAllBoxes(Shapes.DoubleLineConsumer action) {
        DoubleList coords = this.getCoords(Direction.Axis.X);
        DoubleList coords1 = this.getCoords(Direction.Axis.Y);
        DoubleList coords2 = this.getCoords(Direction.Axis.Z);
        this.shape
            .forAllBoxes(
                (x1, y1, z1, x2, y2, z2) -> action.consume(
                    coords.getDouble(x1), coords1.getDouble(y1), coords2.getDouble(z1), coords.getDouble(x2), coords1.getDouble(y2), coords2.getDouble(z2)
                ),
                true
            );
    }

    public List<AABB> toAabbs() {
        List<AABB> list = Lists.newArrayList();
        this.forAllBoxes((x1, y1, z1, x2, y2, z2) -> list.add(new AABB(x1, y1, z1, x2, y2, z2)));
        return list;
    }

    public double min(Direction.Axis axis, double primaryPosition, double secondaryPosition) {
        Direction.Axis axis1 = AxisCycle.FORWARD.cycle(axis);
        Direction.Axis axis2 = AxisCycle.BACKWARD.cycle(axis);
        int i = this.findIndex(axis1, primaryPosition);
        int i1 = this.findIndex(axis2, secondaryPosition);
        int i2 = this.shape.firstFull(axis, i, i1);
        return i2 >= this.shape.getSize(axis) ? Double.POSITIVE_INFINITY : this.get(axis, i2);
    }

    public double max(Direction.Axis axis, double primaryPosition, double secondaryPosition) {
        Direction.Axis axis1 = AxisCycle.FORWARD.cycle(axis);
        Direction.Axis axis2 = AxisCycle.BACKWARD.cycle(axis);
        int i = this.findIndex(axis1, primaryPosition);
        int i1 = this.findIndex(axis2, secondaryPosition);
        int i2 = this.shape.lastFull(axis, i, i1);
        return i2 <= 0 ? Double.NEGATIVE_INFINITY : this.get(axis, i2);
    }

    protected int findIndex(Direction.Axis axis, double position) {
        return Mth.binarySearch(0, this.shape.getSize(axis) + 1, value -> position < this.get(axis, value)) - 1;
    }

    public @Nullable BlockHitResult clip(Vec3 startVec, Vec3 endVec, BlockPos pos) {
        if (this.isEmpty()) {
            return null;
        } else {
            Vec3 vec3 = endVec.subtract(startVec);
            if (vec3.lengthSqr() < 1.0E-7) {
                return null;
            } else {
                Vec3 vec31 = startVec.add(vec3.scale(0.001));
                return this.shape
                        .isFullWide(
                            this.findIndex(Direction.Axis.X, vec31.x - pos.getX()),
                            this.findIndex(Direction.Axis.Y, vec31.y - pos.getY()),
                            this.findIndex(Direction.Axis.Z, vec31.z - pos.getZ())
                        )
                    ? new BlockHitResult(vec31, Direction.getApproximateNearest(vec3.x, vec3.y, vec3.z).getOpposite(), pos, true)
                    : AABB.clip(this.toAabbs(), startVec, endVec, pos);
            }
        }
    }

    public Optional<Vec3> closestPointTo(Vec3 point) {
        if (this.isEmpty()) {
            return Optional.empty();
        } else {
            MutableObject<Vec3> mutableObject = new MutableObject<>();
            this.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
                double d = Mth.clamp(point.x(), x1, x2);
                double d1 = Mth.clamp(point.y(), y1, y2);
                double d2 = Mth.clamp(point.z(), z1, z2);
                Vec3 vec3 = mutableObject.get();
                if (vec3 == null || point.distanceToSqr(d, d1, d2) < point.distanceToSqr(vec3)) {
                    mutableObject.setValue(new Vec3(d, d1, d2));
                }
            });
            return Optional.of(Objects.requireNonNull(mutableObject.get()));
        }
    }

    public VoxelShape getFaceShape(Direction side) {
        if (!this.isEmpty() && this != Shapes.block()) {
            if (this.faces != null) {
                VoxelShape voxelShape = this.faces[side.ordinal()];
                if (voxelShape != null) {
                    return voxelShape;
                }
            } else {
                this.faces = new VoxelShape[6];
            }

            VoxelShape voxelShape = this.calculateFace(side);
            this.faces[side.ordinal()] = voxelShape;
            return voxelShape;
        } else {
            return this;
        }
    }

    private VoxelShape calculateFace(Direction side) {
        Direction.Axis axis = side.getAxis();
        if (this.isCubeLikeAlong(axis)) {
            return this;
        } else {
            Direction.AxisDirection axisDirection = side.getAxisDirection();
            int i = this.findIndex(axis, axisDirection == Direction.AxisDirection.POSITIVE ? 0.9999999 : 1.0E-7);
            SliceShape sliceShape = new SliceShape(this, axis, i);
            if (sliceShape.isEmpty()) {
                return Shapes.empty();
            } else {
                return (VoxelShape)(sliceShape.isCubeLike() ? Shapes.block() : sliceShape);
            }
        }
    }

    protected boolean isCubeLike() {
        for (Direction.Axis axis : Direction.Axis.VALUES) {
            if (!this.isCubeLikeAlong(axis)) {
                return false;
            }
        }

        return true;
    }

    private boolean isCubeLikeAlong(Direction.Axis axis) {
        DoubleList coords = this.getCoords(axis);
        return coords.size() == 2 && DoubleMath.fuzzyEquals(coords.getDouble(0), 0.0, 1.0E-7) && DoubleMath.fuzzyEquals(coords.getDouble(1), 1.0, 1.0E-7);
    }

    public double collide(Direction.Axis movementAxis, AABB collisionBox, double desiredOffset) {
        return this.collideX(AxisCycle.between(movementAxis, Direction.Axis.X), collisionBox, desiredOffset);
    }

    protected double collideX(AxisCycle movementAxis, AABB collisionBox, double desiredOffset) {
        if (this.isEmpty()) {
            return desiredOffset;
        } else if (Math.abs(desiredOffset) < 1.0E-7) {
            return 0.0;
        } else {
            AxisCycle axisCycle = movementAxis.inverse();
            Direction.Axis axis = axisCycle.cycle(Direction.Axis.X);
            Direction.Axis axis1 = axisCycle.cycle(Direction.Axis.Y);
            Direction.Axis axis2 = axisCycle.cycle(Direction.Axis.Z);
            double d = collisionBox.max(axis);
            double d1 = collisionBox.min(axis);
            int i = this.findIndex(axis, d1 + 1.0E-7);
            int i1 = this.findIndex(axis, d - 1.0E-7);
            int max = Math.max(0, this.findIndex(axis1, collisionBox.min(axis1) + 1.0E-7));
            int min = Math.min(this.shape.getSize(axis1), this.findIndex(axis1, collisionBox.max(axis1) - 1.0E-7) + 1);
            int max1 = Math.max(0, this.findIndex(axis2, collisionBox.min(axis2) + 1.0E-7));
            int min1 = Math.min(this.shape.getSize(axis2), this.findIndex(axis2, collisionBox.max(axis2) - 1.0E-7) + 1);
            int size = this.shape.getSize(axis);
            if (desiredOffset > 0.0) {
                for (int i2 = i1 + 1; i2 < size; i2++) {
                    for (int i3 = max; i3 < min; i3++) {
                        for (int i4 = max1; i4 < min1; i4++) {
                            if (this.shape.isFullWide(axisCycle, i2, i3, i4)) {
                                double d2 = this.get(axis, i2) - d;
                                if (d2 >= -1.0E-7) {
                                    desiredOffset = Math.min(desiredOffset, d2);
                                }

                                return desiredOffset;
                            }
                        }
                    }
                }
            } else if (desiredOffset < 0.0) {
                for (int i2 = i - 1; i2 >= 0; i2--) {
                    for (int i3 = max; i3 < min; i3++) {
                        for (int i4x = max1; i4x < min1; i4x++) {
                            if (this.shape.isFullWide(axisCycle, i2, i3, i4x)) {
                                double d2 = this.get(axis, i2 + 1) - d1;
                                if (d2 <= 1.0E-7) {
                                    desiredOffset = Math.max(desiredOffset, d2);
                                }

                                return desiredOffset;
                            }
                        }
                    }
                }
            }

            return desiredOffset;
        }
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other);
    }

    @Override
    public String toString() {
        return this.isEmpty() ? "EMPTY" : "VoxelShape[" + this.bounds() + "]";
    }
}
