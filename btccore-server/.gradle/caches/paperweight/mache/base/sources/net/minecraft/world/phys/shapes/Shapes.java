package net.minecraft.world.phys.shapes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.math.DoubleMath;
import com.google.common.math.IntMath;
import com.mojang.math.OctahedralGroup;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Shapes {
    public static final double EPSILON = 1.0E-7;
    public static final double BIG_EPSILON = 1.0E-6;
    private static final VoxelShape BLOCK = Util.make(() -> {
        DiscreteVoxelShape discreteVoxelShape = new BitSetDiscreteVoxelShape(1, 1, 1);
        discreteVoxelShape.fill(0, 0, 0);
        return new CubeVoxelShape(discreteVoxelShape);
    });
    private static final Vec3 BLOCK_CENTER = new Vec3(0.5, 0.5, 0.5);
    public static final VoxelShape INFINITY = box(
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY
    );
    private static final VoxelShape EMPTY = new ArrayVoxelShape(
        new BitSetDiscreteVoxelShape(0, 0, 0),
        new DoubleArrayList(new double[]{0.0}),
        new DoubleArrayList(new double[]{0.0}),
        new DoubleArrayList(new double[]{0.0})
    );

    public static VoxelShape empty() {
        return EMPTY;
    }

    public static VoxelShape block() {
        return BLOCK;
    }

    public static VoxelShape box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(minX > maxX) && !(minY > maxY) && !(minZ > maxZ)) {
            return create(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            throw new IllegalArgumentException("The min values need to be smaller or equals to the max values");
        }
    }

    public static VoxelShape create(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!(maxX - minX < 1.0E-7) && !(maxY - minY < 1.0E-7) && !(maxZ - minZ < 1.0E-7)) {
            int i = findBits(minX, maxX);
            int i1 = findBits(minY, maxY);
            int i2 = findBits(minZ, maxZ);
            if (i < 0 || i1 < 0 || i2 < 0) {
                return new ArrayVoxelShape(
                    BLOCK.shape,
                    DoubleArrayList.wrap(new double[]{minX, maxX}),
                    DoubleArrayList.wrap(new double[]{minY, maxY}),
                    DoubleArrayList.wrap(new double[]{minZ, maxZ})
                );
            } else if (i == 0 && i1 == 0 && i2 == 0) {
                return block();
            } else {
                int i3 = 1 << i;
                int i4 = 1 << i1;
                int i5 = 1 << i2;
                BitSetDiscreteVoxelShape bitSetDiscreteVoxelShape = BitSetDiscreteVoxelShape.withFilledBounds(
                    i3,
                    i4,
                    i5,
                    (int)Math.round(minX * i3),
                    (int)Math.round(minY * i4),
                    (int)Math.round(minZ * i5),
                    (int)Math.round(maxX * i3),
                    (int)Math.round(maxY * i4),
                    (int)Math.round(maxZ * i5)
                );
                return new CubeVoxelShape(bitSetDiscreteVoxelShape);
            }
        } else {
            return empty();
        }
    }

    public static VoxelShape create(AABB aabb) {
        return create(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
    }

    @VisibleForTesting
    protected static int findBits(double minBits, double maxBits) {
        if (!(minBits < -1.0E-7) && !(maxBits > 1.0000001)) {
            for (int i = 0; i <= 3; i++) {
                int i1 = 1 << i;
                double d = minBits * i1;
                double d1 = maxBits * i1;
                boolean flag = Math.abs(d - Math.round(d)) < 1.0E-7 * i1;
                boolean flag1 = Math.abs(d1 - Math.round(d1)) < 1.0E-7 * i1;
                if (flag && flag1) {
                    return i;
                }
            }

            return -1;
        } else {
            return -1;
        }
    }

    protected static long lcm(int aa, int bb) {
        return (long)aa * (bb / IntMath.gcd(aa, bb));
    }

    public static VoxelShape or(VoxelShape shape1, VoxelShape shape2) {
        return join(shape1, shape2, BooleanOp.OR);
    }

    public static VoxelShape or(VoxelShape shape1, VoxelShape... others) {
        return Arrays.stream(others).reduce(shape1, Shapes::or);
    }

    public static VoxelShape join(VoxelShape shape1, VoxelShape shape2, BooleanOp function) {
        return joinUnoptimized(shape1, shape2, function).optimize();
    }

    public static VoxelShape joinUnoptimized(VoxelShape shape1, VoxelShape shape2, BooleanOp function) {
        if (function.apply(false, false)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException());
        } else if (shape1 == shape2) {
            return function.apply(true, true) ? shape1 : empty();
        } else {
            boolean flag = function.apply(true, false);
            boolean flag1 = function.apply(false, true);
            if (shape1.isEmpty()) {
                return flag1 ? shape2 : empty();
            } else if (shape2.isEmpty()) {
                return flag ? shape1 : empty();
            } else {
                IndexMerger indexMerger = createIndexMerger(1, shape1.getCoords(Direction.Axis.X), shape2.getCoords(Direction.Axis.X), flag, flag1);
                IndexMerger indexMerger1 = createIndexMerger(
                    indexMerger.size() - 1, shape1.getCoords(Direction.Axis.Y), shape2.getCoords(Direction.Axis.Y), flag, flag1
                );
                IndexMerger indexMerger2 = createIndexMerger(
                    (indexMerger.size() - 1) * (indexMerger1.size() - 1), shape1.getCoords(Direction.Axis.Z), shape2.getCoords(Direction.Axis.Z), flag, flag1
                );
                BitSetDiscreteVoxelShape bitSetDiscreteVoxelShape = BitSetDiscreteVoxelShape.join(
                    shape1.shape, shape2.shape, indexMerger, indexMerger1, indexMerger2, function
                );
                return (VoxelShape)(indexMerger instanceof DiscreteCubeMerger
                        && indexMerger1 instanceof DiscreteCubeMerger
                        && indexMerger2 instanceof DiscreteCubeMerger
                    ? new CubeVoxelShape(bitSetDiscreteVoxelShape)
                    : new ArrayVoxelShape(bitSetDiscreteVoxelShape, indexMerger.getList(), indexMerger1.getList(), indexMerger2.getList()));
            }
        }
    }

    public static boolean joinIsNotEmpty(VoxelShape shape1, VoxelShape shape2, BooleanOp resultOperator) {
        if (resultOperator.apply(false, false)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException());
        } else {
            boolean isEmpty = shape1.isEmpty();
            boolean isEmpty1 = shape2.isEmpty();
            if (!isEmpty && !isEmpty1) {
                if (shape1 == shape2) {
                    return resultOperator.apply(true, true);
                } else {
                    boolean flag = resultOperator.apply(true, false);
                    boolean flag1 = resultOperator.apply(false, true);

                    for (Direction.Axis axis : AxisCycle.AXIS_VALUES) {
                        if (shape1.max(axis) < shape2.min(axis) - 1.0E-7) {
                            return flag || flag1;
                        }

                        if (shape2.max(axis) < shape1.min(axis) - 1.0E-7) {
                            return flag || flag1;
                        }
                    }

                    IndexMerger indexMerger = createIndexMerger(1, shape1.getCoords(Direction.Axis.X), shape2.getCoords(Direction.Axis.X), flag, flag1);
                    IndexMerger indexMerger1 = createIndexMerger(
                        indexMerger.size() - 1, shape1.getCoords(Direction.Axis.Y), shape2.getCoords(Direction.Axis.Y), flag, flag1
                    );
                    IndexMerger indexMerger2 = createIndexMerger(
                        (indexMerger.size() - 1) * (indexMerger1.size() - 1),
                        shape1.getCoords(Direction.Axis.Z),
                        shape2.getCoords(Direction.Axis.Z),
                        flag,
                        flag1
                    );
                    return joinIsNotEmpty(indexMerger, indexMerger1, indexMerger2, shape1.shape, shape2.shape, resultOperator);
                }
            } else {
                return resultOperator.apply(!isEmpty, !isEmpty1);
            }
        }
    }

    private static boolean joinIsNotEmpty(
        IndexMerger mergerX,
        IndexMerger mergerY,
        IndexMerger mergerZ,
        DiscreteVoxelShape primaryShape,
        DiscreteVoxelShape secondaryShape,
        BooleanOp resultOperator
    ) {
        return !mergerX.forMergedIndexes(
            (x1, y1, z1) -> mergerY.forMergedIndexes(
                (x2, y2, z2) -> mergerZ.forMergedIndexes(
                    (x3, y3, z3) -> !resultOperator.apply(primaryShape.isFullWide(x1, x2, x3), secondaryShape.isFullWide(y1, y2, y3))
                )
            )
        );
    }

    public static double collide(Direction.Axis movementAxis, AABB collisionBox, Iterable<VoxelShape> possibleHits, double desiredOffset) {
        for (VoxelShape voxelShape : possibleHits) {
            if (Math.abs(desiredOffset) < 1.0E-7) {
                return 0.0;
            }

            desiredOffset = voxelShape.collide(movementAxis, collisionBox, desiredOffset);
        }

        return desiredOffset;
    }

    public static boolean blockOccludes(VoxelShape shape, VoxelShape adjacentShape, Direction side) {
        if (shape == block() && adjacentShape == block()) {
            return true;
        } else if (adjacentShape.isEmpty()) {
            return false;
        } else {
            Direction.Axis axis = side.getAxis();
            Direction.AxisDirection axisDirection = side.getAxisDirection();
            VoxelShape voxelShape = axisDirection == Direction.AxisDirection.POSITIVE ? shape : adjacentShape;
            VoxelShape voxelShape1 = axisDirection == Direction.AxisDirection.POSITIVE ? adjacentShape : shape;
            BooleanOp booleanOp = axisDirection == Direction.AxisDirection.POSITIVE ? BooleanOp.ONLY_FIRST : BooleanOp.ONLY_SECOND;
            return DoubleMath.fuzzyEquals(voxelShape.max(axis), 1.0, 1.0E-7)
                && DoubleMath.fuzzyEquals(voxelShape1.min(axis), 0.0, 1.0E-7)
                && !joinIsNotEmpty(new SliceShape(voxelShape, axis, voxelShape.shape.getSize(axis) - 1), new SliceShape(voxelShape1, axis, 0), booleanOp);
        }
    }

    public static boolean mergedFaceOccludes(VoxelShape shape, VoxelShape adjacentShape, Direction side) {
        if (shape != block() && adjacentShape != block()) {
            Direction.Axis axis = side.getAxis();
            Direction.AxisDirection axisDirection = side.getAxisDirection();
            VoxelShape voxelShape = axisDirection == Direction.AxisDirection.POSITIVE ? shape : adjacentShape;
            VoxelShape voxelShape1 = axisDirection == Direction.AxisDirection.POSITIVE ? adjacentShape : shape;
            if (!DoubleMath.fuzzyEquals(voxelShape.max(axis), 1.0, 1.0E-7)) {
                voxelShape = empty();
            }

            if (!DoubleMath.fuzzyEquals(voxelShape1.min(axis), 0.0, 1.0E-7)) {
                voxelShape1 = empty();
            }

            return !joinIsNotEmpty(
                block(),
                joinUnoptimized(new SliceShape(voxelShape, axis, voxelShape.shape.getSize(axis) - 1), new SliceShape(voxelShape1, axis, 0), BooleanOp.OR),
                BooleanOp.ONLY_FIRST
            );
        } else {
            return true;
        }
    }

    public static boolean faceShapeOccludes(VoxelShape shape, VoxelShape adjacentShape) {
        return shape == block()
            || adjacentShape == block()
            || (!shape.isEmpty() || !adjacentShape.isEmpty())
                && !joinIsNotEmpty(block(), joinUnoptimized(shape, adjacentShape, BooleanOp.OR), BooleanOp.ONLY_FIRST);
    }

    @VisibleForTesting
    protected static IndexMerger createIndexMerger(int size, DoubleList list1, DoubleList list2, boolean excludeUpper, boolean excludeLower) {
        int i = list1.size() - 1;
        int i1 = list2.size() - 1;
        if (list1 instanceof CubePointRange && list2 instanceof CubePointRange) {
            long l = lcm(i, i1);
            if (size * l <= 256L) {
                return new DiscreteCubeMerger(i, i1);
            }
        }

        if (list1.getDouble(i) < list2.getDouble(0) - 1.0E-7) {
            return new NonOverlappingMerger(list1, list2, false);
        } else if (list2.getDouble(i1) < list1.getDouble(0) - 1.0E-7) {
            return new NonOverlappingMerger(list2, list1, true);
        } else {
            return (IndexMerger)(i == i1 && Objects.equals(list1, list2)
                ? new IdenticalMerger(list1)
                : new IndirectMerger(list1, list2, excludeUpper, excludeLower));
        }
    }

    public static VoxelShape rotate(VoxelShape shape, OctahedralGroup rotation) {
        return rotate(shape, rotation, BLOCK_CENTER);
    }

    public static VoxelShape rotate(VoxelShape shape, OctahedralGroup rotation, Vec3 rotationPoint) {
        if (rotation == OctahedralGroup.IDENTITY) {
            return shape;
        } else {
            DiscreteVoxelShape discreteVoxelShape = shape.shape.rotate(rotation);
            if (shape instanceof CubeVoxelShape && BLOCK_CENTER.equals(rotationPoint)) {
                return new CubeVoxelShape(discreteVoxelShape);
            } else {
                Direction.Axis axis = rotation.permutation().permuteAxis(Direction.Axis.X);
                Direction.Axis axis1 = rotation.permutation().permuteAxis(Direction.Axis.Y);
                Direction.Axis axis2 = rotation.permutation().permuteAxis(Direction.Axis.Z);
                DoubleList coords = shape.getCoords(axis);
                DoubleList coords1 = shape.getCoords(axis1);
                DoubleList coords2 = shape.getCoords(axis2);
                boolean flag = rotation.inverts(Direction.Axis.X);
                boolean flag1 = rotation.inverts(Direction.Axis.Y);
                boolean flag2 = rotation.inverts(Direction.Axis.Z);
                return new ArrayVoxelShape(
                    discreteVoxelShape,
                    flipAxisIfNeeded(coords, flag, rotationPoint.get(axis), rotationPoint.x),
                    flipAxisIfNeeded(coords1, flag1, rotationPoint.get(axis1), rotationPoint.y),
                    flipAxisIfNeeded(coords2, flag2, rotationPoint.get(axis2), rotationPoint.z)
                );
            }
        }
    }

    @VisibleForTesting
    static DoubleList flipAxisIfNeeded(DoubleList coords, boolean invert, double from, double to) {
        if (!invert && from == to) {
            return coords;
        } else {
            int size = coords.size();
            DoubleList list = new DoubleArrayList(size);
            if (invert) {
                for (int i = size - 1; i >= 0; i--) {
                    list.add(-(coords.getDouble(i) - from) + to);
                }
            } else {
                for (int i = 0; i >= 0 && i < size; i++) {
                    list.add(coords.getDouble(i) - from + to);
                }
            }

            return list;
        }
    }

    public static boolean equal(VoxelShape first, VoxelShape second) {
        return !joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(VoxelShape zAxis) {
        return rotateHorizontalAxis(zAxis, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateHorizontalAxis(VoxelShape zAxis, Vec3 rotationCenter) {
        return Maps.newEnumMap(Map.of(Direction.Axis.Z, zAxis, Direction.Axis.X, rotate(zAxis, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter)));
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(VoxelShape north) {
        return rotateAllAxis(north, BLOCK_CENTER);
    }

    public static Map<Direction.Axis, VoxelShape> rotateAllAxis(VoxelShape north, Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.Axis.Z,
                north,
                Direction.Axis.X,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90, rotationCenter),
                Direction.Axis.Y,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90, rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(VoxelShape north) {
        return rotateHorizontal(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(VoxelShape north, OctahedralGroup initial) {
        return rotateHorizontal(north, initial, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateHorizontal(VoxelShape north, OctahedralGroup initial, Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<Direction, VoxelShape> rotateAll(VoxelShape north) {
        return rotateAll(north, OctahedralGroup.IDENTITY, BLOCK_CENTER);
    }

    public static Map<Direction, VoxelShape> rotateAll(VoxelShape north, Vec3 rotationCenter) {
        return rotateAll(north, OctahedralGroup.IDENTITY, rotationCenter);
    }

    public static Map<Direction, VoxelShape> rotateAll(VoxelShape north, OctahedralGroup initial, Vec3 rotationCenter) {
        return Maps.newEnumMap(
            Map.of(
                Direction.NORTH,
                rotate(north, initial),
                Direction.EAST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_90.compose(initial), rotationCenter),
                Direction.SOUTH,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(initial), rotationCenter),
                Direction.WEST,
                rotate(north, OctahedralGroup.BLOCK_ROT_Y_270.compose(initial), rotationCenter),
                Direction.UP,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial), rotationCenter),
                Direction.DOWN,
                rotate(north, OctahedralGroup.BLOCK_ROT_X_90.compose(initial), rotationCenter)
            )
        );
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(VoxelShape north) {
        return rotateAttachFace(north, OctahedralGroup.IDENTITY);
    }

    public static Map<AttachFace, Map<Direction, VoxelShape>> rotateAttachFace(VoxelShape north, OctahedralGroup initial) {
        return Map.of(
            AttachFace.WALL,
            rotateHorizontal(north, initial),
            AttachFace.FLOOR,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_X_270.compose(initial)),
            AttachFace.CEILING,
            rotateHorizontal(north, OctahedralGroup.BLOCK_ROT_Y_180.compose(OctahedralGroup.BLOCK_ROT_X_90).compose(initial))
        );
    }

    public interface DoubleLineConsumer {
        void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }
}
