package net.minecraft.world.level.border;

import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class WorldBorder extends SavedData {
    public static final double MAX_SIZE = 5.999997E7F;
    public static final double MAX_CENTER_COORDINATE = 2.9999984E7;
    public static final Codec<WorldBorder> CODEC = WorldBorder.Settings.CODEC.xmap(WorldBorder::new, WorldBorder.Settings::new);
    public static final SavedDataType<WorldBorder> TYPE = new SavedDataType<>("world_border", WorldBorder::new, CODEC, DataFixTypes.SAVED_DATA_WORLD_BORDER);
    private final WorldBorder.Settings settings;
    private boolean initialized;
    private final List<BorderChangeListener> listeners = Lists.newArrayList();
    double damagePerBlock = 0.2;
    double safeZone = 5.0;
    int warningTime = 15;
    int warningBlocks = 5;
    double centerX;
    double centerZ;
    int absoluteMaxSize = 29999984;
    WorldBorder.BorderExtent extent = new WorldBorder.StaticBorderExtent(5.999997E7F);

    public WorldBorder() {
        this(WorldBorder.Settings.DEFAULT);
    }

    public WorldBorder(WorldBorder.Settings settings) {
        this.settings = settings;
    }

    public boolean isWithinBounds(BlockPos pos) {
        return this.isWithinBounds(pos.getX(), pos.getZ());
    }

    public boolean isWithinBounds(Vec3 pos) {
        return this.isWithinBounds(pos.x, pos.z);
    }

    public boolean isWithinBounds(ChunkPos chunkPos) {
        return this.isWithinBounds(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ()) && this.isWithinBounds(chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ());
    }

    public boolean isWithinBounds(AABB box) {
        return this.isWithinBounds(box.minX, box.minZ, box.maxX - 1.0E-5F, box.maxZ - 1.0E-5F);
    }

    private boolean isWithinBounds(double x1, double z1, double x2, double z2) {
        return this.isWithinBounds(x1, z1) && this.isWithinBounds(x2, z2);
    }

    public boolean isWithinBounds(double x, double z) {
        return this.isWithinBounds(x, z, 0.0);
    }

    public boolean isWithinBounds(double x, double z, double offset) {
        return x >= this.getMinX() - offset && x < this.getMaxX() + offset && z >= this.getMinZ() - offset && z < this.getMaxZ() + offset;
    }

    public BlockPos clampToBounds(BlockPos pos) {
        return this.clampToBounds(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos clampToBounds(Vec3 pos) {
        return this.clampToBounds(pos.x(), pos.y(), pos.z());
    }

    public BlockPos clampToBounds(double x, double y, double z) {
        return BlockPos.containing(this.clampVec3ToBound(x, y, z));
    }

    public Vec3 clampVec3ToBound(Vec3 vec3) {
        return this.clampVec3ToBound(vec3.x, vec3.y, vec3.z);
    }

    public Vec3 clampVec3ToBound(double x, double y, double z) {
        return new Vec3(Mth.clamp(x, this.getMinX(), this.getMaxX() - 1.0E-5F), y, Mth.clamp(z, this.getMinZ(), this.getMaxZ() - 1.0E-5F));
    }

    public double getDistanceToBorder(Entity entity) {
        return this.getDistanceToBorder(entity.getX(), entity.getZ());
    }

    public VoxelShape getCollisionShape() {
        return this.extent.getCollisionShape();
    }

    public double getDistanceToBorder(double x, double z) {
        double d = z - this.getMinZ();
        double d1 = this.getMaxZ() - z;
        double d2 = x - this.getMinX();
        double d3 = this.getMaxX() - x;
        double min = Math.min(d2, d3);
        min = Math.min(min, d);
        return Math.min(min, d1);
    }

    public boolean isInsideCloseToBorder(Entity entity, AABB bounds) {
        double max = Math.max(Mth.absMax(bounds.getXsize(), bounds.getZsize()), 1.0);
        return this.getDistanceToBorder(entity) < max * 2.0 && this.isWithinBounds(entity.getX(), entity.getZ(), max);
    }

    public BorderStatus getStatus() {
        return this.extent.getStatus();
    }

    public double getMinX() {
        return this.getMinX(0.0F);
    }

    public double getMinX(float partialTick) {
        return this.extent.getMinX(partialTick);
    }

    public double getMinZ() {
        return this.getMinZ(0.0F);
    }

    public double getMinZ(float partialTick) {
        return this.extent.getMinZ(partialTick);
    }

    public double getMaxX() {
        return this.getMaxX(0.0F);
    }

    public double getMaxX(float partialTick) {
        return this.extent.getMaxX(partialTick);
    }

    public double getMaxZ() {
        return this.getMaxZ(0.0F);
    }

    public double getMaxZ(float partialTick) {
        return this.extent.getMaxZ(partialTick);
    }

    public double getCenterX() {
        return this.centerX;
    }

    public double getCenterZ() {
        return this.centerZ;
    }

    public void setCenter(double x, double z) {
        this.centerX = x;
        this.centerZ = z;
        this.extent.onCenterChange();
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetCenter(this, x, z);
        }
    }

    public double getSize() {
        return this.extent.getSize();
    }

    public long getLerpTime() {
        return this.extent.getLerpTime();
    }

    public double getLerpTarget() {
        return this.extent.getLerpTarget();
    }

    public void setSize(double size) {
        this.extent = new WorldBorder.StaticBorderExtent(size);
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetSize(this, size);
        }
    }

    public void lerpSizeBetween(double oldSize, double newSize, long time, long startTime) {
        this.extent = (WorldBorder.BorderExtent)(oldSize == newSize
            ? new WorldBorder.StaticBorderExtent(newSize)
            : new WorldBorder.MovingBorderExtent(oldSize, newSize, time, startTime));
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onLerpSize(this, oldSize, newSize, time, startTime);
        }
    }

    protected List<BorderChangeListener> getListeners() {
        return Lists.newArrayList(this.listeners);
    }

    public void addListener(BorderChangeListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(BorderChangeListener listener) {
        this.listeners.remove(listener);
    }

    public void setAbsoluteMaxSize(int size) {
        this.absoluteMaxSize = size;
        this.extent.onAbsoluteMaxSizeChange();
    }

    public int getAbsoluteMaxSize() {
        return this.absoluteMaxSize;
    }

    public double getSafeZone() {
        return this.safeZone;
    }

    public void setSafeZone(double safeZone) {
        this.safeZone = safeZone;
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetSafeZone(this, safeZone);
        }
    }

    public double getDamagePerBlock() {
        return this.damagePerBlock;
    }

    public void setDamagePerBlock(double damagePerBlock) {
        this.damagePerBlock = damagePerBlock;
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetDamagePerBlock(this, damagePerBlock);
        }
    }

    public double getLerpSpeed() {
        return this.extent.getLerpSpeed();
    }

    public int getWarningTime() {
        return this.warningTime;
    }

    public void setWarningTime(int warningTime) {
        this.warningTime = warningTime;
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetWarningTime(this, warningTime);
        }
    }

    public int getWarningBlocks() {
        return this.warningBlocks;
    }

    public void setWarningBlocks(int warningBlocks) {
        this.warningBlocks = warningBlocks;
        this.setDirty();

        for (BorderChangeListener borderChangeListener : this.getListeners()) {
            borderChangeListener.onSetWarningBlocks(this, warningBlocks);
        }
    }

    public void tick() {
        this.extent = this.extent.update();
    }

    public void applyInitialSettings(long lerpBegin) {
        if (!this.initialized) {
            this.setCenter(this.settings.centerX(), this.settings.centerZ());
            this.setDamagePerBlock(this.settings.damagePerBlock());
            this.setSafeZone(this.settings.safeZone());
            this.setWarningBlocks(this.settings.warningBlocks());
            this.setWarningTime(this.settings.warningTime());
            if (this.settings.lerpTime() > 0L) {
                this.lerpSizeBetween(this.settings.size(), this.settings.lerpTarget(), this.settings.lerpTime(), lerpBegin);
            } else {
                this.setSize(this.settings.size());
            }

            this.initialized = true;
        }
    }

    interface BorderExtent {
        double getMinX(float partialTick);

        double getMaxX(float partialTick);

        double getMinZ(float partialTick);

        double getMaxZ(float partialTick);

        double getSize();

        double getLerpSpeed();

        long getLerpTime();

        double getLerpTarget();

        BorderStatus getStatus();

        void onAbsoluteMaxSizeChange();

        void onCenterChange();

        WorldBorder.BorderExtent update();

        VoxelShape getCollisionShape();
    }

    class MovingBorderExtent implements WorldBorder.BorderExtent {
        private final double from;
        private final double to;
        private final long lerpEnd;
        private final long lerpBegin;
        private final double lerpDuration;
        private long lerpProgress;
        private double size;
        private double previousSize;

        MovingBorderExtent(final double from, final double to, final long lerpDuration, final long lerpBegin) {
            this.from = from;
            this.to = to;
            this.lerpDuration = lerpDuration;
            this.lerpProgress = lerpDuration;
            this.lerpBegin = lerpBegin;
            this.lerpEnd = this.lerpBegin + lerpDuration;
            double d = this.calculateSize();
            this.size = d;
            this.previousSize = d;
        }

        @Override
        public double getMinX(float partialTick) {
            return Mth.clamp(
                WorldBorder.this.getCenterX() - Mth.lerp((double)partialTick, this.getPreviousSize(), this.getSize()) / 2.0,
                (double)(-WorldBorder.this.absoluteMaxSize),
                (double)WorldBorder.this.absoluteMaxSize
            );
        }

        @Override
        public double getMinZ(float partialTick) {
            return Mth.clamp(
                WorldBorder.this.getCenterZ() - Mth.lerp((double)partialTick, this.getPreviousSize(), this.getSize()) / 2.0,
                (double)(-WorldBorder.this.absoluteMaxSize),
                (double)WorldBorder.this.absoluteMaxSize
            );
        }

        @Override
        public double getMaxX(float partialTick) {
            return Mth.clamp(
                WorldBorder.this.getCenterX() + Mth.lerp((double)partialTick, this.getPreviousSize(), this.getSize()) / 2.0,
                (double)(-WorldBorder.this.absoluteMaxSize),
                (double)WorldBorder.this.absoluteMaxSize
            );
        }

        @Override
        public double getMaxZ(float partialTick) {
            return Mth.clamp(
                WorldBorder.this.getCenterZ() + Mth.lerp((double)partialTick, this.getPreviousSize(), this.getSize()) / 2.0,
                (double)(-WorldBorder.this.absoluteMaxSize),
                (double)WorldBorder.this.absoluteMaxSize
            );
        }

        @Override
        public double getSize() {
            return this.size;
        }

        public double getPreviousSize() {
            return this.previousSize;
        }

        private double calculateSize() {
            double d = (this.lerpDuration - this.lerpProgress) / this.lerpDuration;
            return d < 1.0 ? Mth.lerp(d, this.from, this.to) : this.to;
        }

        @Override
        public double getLerpSpeed() {
            return Math.abs(this.from - this.to) / (this.lerpEnd - this.lerpBegin);
        }

        @Override
        public long getLerpTime() {
            return this.lerpProgress;
        }

        @Override
        public double getLerpTarget() {
            return this.to;
        }

        @Override
        public BorderStatus getStatus() {
            return this.to < this.from ? BorderStatus.SHRINKING : BorderStatus.GROWING;
        }

        @Override
        public void onCenterChange() {
        }

        @Override
        public void onAbsoluteMaxSizeChange() {
        }

        @Override
        public WorldBorder.BorderExtent update() {
            this.lerpProgress--;
            this.previousSize = this.size;
            this.size = this.calculateSize();
            if (this.lerpProgress <= 0L) {
                WorldBorder.this.setDirty();
                return WorldBorder.this.new StaticBorderExtent(this.to);
            } else {
                return this;
            }
        }

        @Override
        public VoxelShape getCollisionShape() {
            return Shapes.join(
                Shapes.INFINITY,
                Shapes.box(
                    Math.floor(this.getMinX(0.0F)),
                    Double.NEGATIVE_INFINITY,
                    Math.floor(this.getMinZ(0.0F)),
                    Math.ceil(this.getMaxX(0.0F)),
                    Double.POSITIVE_INFINITY,
                    Math.ceil(this.getMaxZ(0.0F))
                ),
                BooleanOp.ONLY_FIRST
            );
        }
    }

    public record Settings(
        double centerX,
        double centerZ,
        double damagePerBlock,
        double safeZone,
        int warningBlocks,
        int warningTime,
        double size,
        long lerpTime,
        double lerpTarget
    ) {
        public static final WorldBorder.Settings DEFAULT = new WorldBorder.Settings(0.0, 0.0, 0.2, 5.0, 5, 300, 5.999997E7F, 0L, 0.0);
        public static final Codec<WorldBorder.Settings> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.doubleRange(-2.9999984E7, 2.9999984E7).fieldOf("center_x").forGetter(WorldBorder.Settings::centerX),
                    Codec.doubleRange(-2.9999984E7, 2.9999984E7).fieldOf("center_z").forGetter(WorldBorder.Settings::centerZ),
                    Codec.DOUBLE.fieldOf("damage_per_block").forGetter(WorldBorder.Settings::damagePerBlock),
                    Codec.DOUBLE.fieldOf("safe_zone").forGetter(WorldBorder.Settings::safeZone),
                    Codec.INT.fieldOf("warning_blocks").forGetter(WorldBorder.Settings::warningBlocks),
                    Codec.INT.fieldOf("warning_time").forGetter(WorldBorder.Settings::warningTime),
                    Codec.DOUBLE.fieldOf("size").forGetter(WorldBorder.Settings::size),
                    Codec.LONG.fieldOf("lerp_time").forGetter(WorldBorder.Settings::lerpTime),
                    Codec.DOUBLE.fieldOf("lerp_target").forGetter(WorldBorder.Settings::lerpTarget)
                )
                .apply(instance, WorldBorder.Settings::new)
        );

        public Settings(WorldBorder border) {
            this(
                border.centerX,
                border.centerZ,
                border.damagePerBlock,
                border.safeZone,
                border.warningBlocks,
                border.warningTime,
                border.extent.getSize(),
                border.extent.getLerpTime(),
                border.extent.getLerpTarget()
            );
        }
    }

    class StaticBorderExtent implements WorldBorder.BorderExtent {
        private final double size;
        private double minX;
        private double minZ;
        private double maxX;
        private double maxZ;
        private VoxelShape shape;

        public StaticBorderExtent(final double size) {
            this.size = size;
            this.updateBox();
        }

        @Override
        public double getMinX(float partialTick) {
            return this.minX;
        }

        @Override
        public double getMaxX(float partialTick) {
            return this.maxX;
        }

        @Override
        public double getMinZ(float partialTick) {
            return this.minZ;
        }

        @Override
        public double getMaxZ(float partialTick) {
            return this.maxZ;
        }

        @Override
        public double getSize() {
            return this.size;
        }

        @Override
        public BorderStatus getStatus() {
            return BorderStatus.STATIONARY;
        }

        @Override
        public double getLerpSpeed() {
            return 0.0;
        }

        @Override
        public long getLerpTime() {
            return 0L;
        }

        @Override
        public double getLerpTarget() {
            return this.size;
        }

        private void updateBox() {
            this.minX = Mth.clamp(
                WorldBorder.this.getCenterX() - this.size / 2.0, (double)(-WorldBorder.this.absoluteMaxSize), (double)WorldBorder.this.absoluteMaxSize
            );
            this.minZ = Mth.clamp(
                WorldBorder.this.getCenterZ() - this.size / 2.0, (double)(-WorldBorder.this.absoluteMaxSize), (double)WorldBorder.this.absoluteMaxSize
            );
            this.maxX = Mth.clamp(
                WorldBorder.this.getCenterX() + this.size / 2.0, (double)(-WorldBorder.this.absoluteMaxSize), (double)WorldBorder.this.absoluteMaxSize
            );
            this.maxZ = Mth.clamp(
                WorldBorder.this.getCenterZ() + this.size / 2.0, (double)(-WorldBorder.this.absoluteMaxSize), (double)WorldBorder.this.absoluteMaxSize
            );
            this.shape = Shapes.join(
                Shapes.INFINITY,
                Shapes.box(
                    Math.floor(this.getMinX(0.0F)),
                    Double.NEGATIVE_INFINITY,
                    Math.floor(this.getMinZ(0.0F)),
                    Math.ceil(this.getMaxX(0.0F)),
                    Double.POSITIVE_INFINITY,
                    Math.ceil(this.getMaxZ(0.0F))
                ),
                BooleanOp.ONLY_FIRST
            );
        }

        @Override
        public void onAbsoluteMaxSizeChange() {
            this.updateBox();
        }

        @Override
        public void onCenterChange() {
            this.updateBox();
        }

        @Override
        public WorldBorder.BorderExtent update() {
            return this;
        }

        @Override
        public VoxelShape getCollisionShape() {
            return this.shape;
        }
    }
}
