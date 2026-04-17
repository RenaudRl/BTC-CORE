package net.minecraft.world.entity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import it.unimi.dsi.fastutil.floats.FloatSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.debug.DebugEntityBlockIntersection;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Nameable;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Team;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class Entity implements SyncedDataHolder, DebugValueSource, Nameable, ItemOwner, SlotProvider, EntityAccess, ScoreHolder, DataComponentGetter {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String TAG_ID = "id";
    public static final String TAG_UUID = "UUID";
    public static final String TAG_PASSENGERS = "Passengers";
    public static final String TAG_DATA = "data";
    public static final String TAG_POS = "Pos";
    public static final String TAG_MOTION = "Motion";
    public static final String TAG_ROTATION = "Rotation";
    public static final String TAG_PORTAL_COOLDOWN = "PortalCooldown";
    public static final String TAG_NO_GRAVITY = "NoGravity";
    public static final String TAG_AIR = "Air";
    public static final String TAG_ON_GROUND = "OnGround";
    public static final String TAG_FALL_DISTANCE = "fall_distance";
    public static final String TAG_FIRE = "Fire";
    public static final String TAG_SILENT = "Silent";
    public static final String TAG_GLOWING = "Glowing";
    public static final String TAG_INVULNERABLE = "Invulnerable";
    public static final String TAG_CUSTOM_NAME = "CustomName";
    private static final AtomicInteger ENTITY_COUNTER = new AtomicInteger();
    public static final int CONTENTS_SLOT_INDEX = 0;
    public static final int BOARDING_COOLDOWN = 60;
    public static final int TOTAL_AIR_SUPPLY = 300;
    public static final int MAX_ENTITY_TAG_COUNT = 1024;
    private static final Codec<List<String>> TAG_LIST_CODEC = Codec.STRING.sizeLimitedListOf(1024);
    public static final float DELTA_AFFECTED_BY_BLOCKS_BELOW_0_2 = 0.2F;
    public static final double DELTA_AFFECTED_BY_BLOCKS_BELOW_0_5 = 0.500001;
    public static final double DELTA_AFFECTED_BY_BLOCKS_BELOW_1_0 = 0.999999;
    public static final int BASE_TICKS_REQUIRED_TO_FREEZE = 140;
    public static final int FREEZE_HURT_FREQUENCY = 40;
    public static final int BASE_SAFE_FALL_DISTANCE = 3;
    private static final AABB INITIAL_AABB = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    private static final double WATER_FLOW_SCALE = 0.014;
    private static final double LAVA_FAST_FLOW_SCALE = 0.007;
    private static final double LAVA_SLOW_FLOW_SCALE = 0.0023333333333333335;
    private static final int MAX_BLOCK_ITERATIONS_ALONG_TRAVEL_PER_TICK = 16;
    private static final double MAX_MOVEMENT_RESETTING_TRACE_DISTANCE = 8.0;
    private static double viewScale = 1.0;
    private final EntityType<?> type;
    private boolean requiresPrecisePosition;
    private int id = ENTITY_COUNTER.incrementAndGet();
    public boolean blocksBuilding;
    public ImmutableList<Entity> passengers = ImmutableList.of();
    protected int boardingCooldown;
    private @Nullable Entity vehicle;
    private Level level;
    public double xo;
    public double yo;
    public double zo;
    private Vec3 position;
    private BlockPos blockPosition;
    private ChunkPos chunkPosition;
    private Vec3 deltaMovement = Vec3.ZERO;
    private float yRot;
    private float xRot;
    public float yRotO;
    public float xRotO;
    private AABB bb = INITIAL_AABB;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean verticalCollisionBelow;
    public boolean minorHorizontalCollision;
    public boolean hurtMarked;
    protected Vec3 stuckSpeedMultiplier = Vec3.ZERO;
    private Entity.@Nullable RemovalReason removalReason;
    public static final float DEFAULT_BB_WIDTH = 0.6F;
    public static final float DEFAULT_BB_HEIGHT = 1.8F;
    public float moveDist;
    public float flyDist;
    public double fallDistance;
    private float nextStep = 1.0F;
    public double xOld;
    public double yOld;
    public double zOld;
    public boolean noPhysics;
    public final RandomSource random = RandomSource.create();
    public int tickCount;
    private int remainingFireTicks;
    public boolean wasTouchingWater;
    protected Object2DoubleMap<TagKey<Fluid>> fluidHeight = new Object2DoubleArrayMap<>(2);
    protected boolean wasEyeInWater;
    private final Set<TagKey<Fluid>> fluidOnEyes = new HashSet<>();
    public int invulnerableTime;
    protected boolean firstTick = true;
    protected final SynchedEntityData entityData;
    protected static final EntityDataAccessor<Byte> DATA_SHARED_FLAGS_ID = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BYTE);
    protected static final int FLAG_ONFIRE = 0;
    private static final int FLAG_SHIFT_KEY_DOWN = 1;
    private static final int FLAG_SPRINTING = 3;
    private static final int FLAG_SWIMMING = 4;
    public static final int FLAG_INVISIBLE = 5;
    protected static final int FLAG_GLOWING = 6;
    protected static final int FLAG_FALL_FLYING = 7;
    private static final EntityDataAccessor<Integer> DATA_AIR_SUPPLY_ID = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<Component>> DATA_CUSTOM_NAME = SynchedEntityData.defineId(
        Entity.class, EntityDataSerializers.OPTIONAL_COMPONENT
    );
    private static final EntityDataAccessor<Boolean> DATA_CUSTOM_NAME_VISIBLE = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_SILENT = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_NO_GRAVITY = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    protected static final EntityDataAccessor<Pose> DATA_POSE = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.POSE);
    public static final EntityDataAccessor<Integer> DATA_TICKS_FROZEN = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);
    private EntityInLevelCallback levelCallback = EntityInLevelCallback.NULL;
    private final VecDeltaCodec packetPositionCodec = new VecDeltaCodec();
    public boolean needsSync;
    public @Nullable PortalProcessor portalProcess;
    public int portalCooldown;
    private boolean invulnerable;
    protected UUID uuid = Mth.createInsecureUUID(this.random);
    protected String stringUUID = this.uuid.toString();
    private boolean hasGlowingTag;
    private final Set<String> tags = Sets.newHashSet();
    private final double[] pistonDeltas = new double[]{0.0, 0.0, 0.0};
    private long pistonDeltasGameTime;
    private EntityDimensions dimensions;
    private float eyeHeight;
    public boolean isInPowderSnow;
    public boolean wasInPowderSnow;
    public Optional<BlockPos> mainSupportingBlockPos = Optional.empty();
    private boolean onGroundNoBlocks = false;
    private float crystalSoundIntensity;
    private int lastCrystalSoundPlayTick;
    public boolean hasVisualFire;
    private Vec3 lastKnownSpeed = Vec3.ZERO;
    private @Nullable Vec3 lastKnownPosition;
    private @Nullable BlockState inBlockState = null;
    public static final int MAX_MOVEMENTS_HANDELED_PER_TICK = 100;
    private final ArrayDeque<Entity.Movement> movementThisTick = new ArrayDeque<>(100);
    private final List<Entity.Movement> finalMovementsThisTick = new ObjectArrayList<>();
    private final LongSet visitedBlocks = new LongOpenHashSet();
    private final InsideBlockEffectApplier.StepBasedCollector insideEffectCollector = new InsideBlockEffectApplier.StepBasedCollector();
    private CustomData customData = CustomData.EMPTY;

    public Entity(EntityType<?> type, Level level) {
        this.type = type;
        this.level = level;
        this.dimensions = type.getDimensions();
        this.position = Vec3.ZERO;
        this.blockPosition = BlockPos.ZERO;
        this.chunkPosition = ChunkPos.ZERO;
        SynchedEntityData.Builder builder = new SynchedEntityData.Builder(this);
        builder.define(DATA_SHARED_FLAGS_ID, (byte)0);
        builder.define(DATA_AIR_SUPPLY_ID, this.getMaxAirSupply());
        builder.define(DATA_CUSTOM_NAME_VISIBLE, false);
        builder.define(DATA_CUSTOM_NAME, Optional.empty());
        builder.define(DATA_SILENT, false);
        builder.define(DATA_NO_GRAVITY, false);
        builder.define(DATA_POSE, Pose.STANDING);
        builder.define(DATA_TICKS_FROZEN, 0);
        this.defineSynchedData(builder);
        this.entityData = builder.build();
        this.setPos(0.0, 0.0, 0.0);
        this.eyeHeight = this.dimensions.eyeHeight();
    }

    public boolean isColliding(BlockPos pos, BlockState state) {
        VoxelShape voxelShape = state.getCollisionShape(this.level(), pos, CollisionContext.of(this)).move(pos);
        return Shapes.joinIsNotEmpty(voxelShape, Shapes.create(this.getBoundingBox()), BooleanOp.AND);
    }

    public int getTeamColor() {
        Team team = this.getTeam();
        return team != null && team.getColor().getColor() != null ? team.getColor().getColor() : 16777215;
    }

    public boolean isSpectator() {
        return false;
    }

    public boolean canInteractWithLevel() {
        return this.isAlive() && !this.isRemoved() && !this.isSpectator();
    }

    public final void unRide() {
        if (this.isVehicle()) {
            this.ejectPassengers();
        }

        if (this.isPassenger()) {
            this.stopRiding();
        }
    }

    public void syncPacketPositionCodec(double x, double y, double z) {
        this.packetPositionCodec.setBase(new Vec3(x, y, z));
    }

    public VecDeltaCodec getPositionCodec() {
        return this.packetPositionCodec;
    }

    public EntityType<?> getType() {
        return this.type;
    }

    public boolean getRequiresPrecisePosition() {
        return this.requiresPrecisePosition;
    }

    public void setRequiresPrecisePosition(boolean requiresPrecisePosition) {
        this.requiresPrecisePosition = requiresPrecisePosition;
    }

    @Override
    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Set<String> getTags() {
        return this.tags;
    }

    public boolean addTag(String tag) {
        return this.tags.size() < 1024 && this.tags.add(tag);
    }

    public boolean removeTag(String tag) {
        return this.tags.remove(tag);
    }

    public void kill(ServerLevel level) {
        this.remove(Entity.RemovalReason.KILLED);
        this.gameEvent(GameEvent.ENTITY_DIE);
    }

    public final void discard() {
        this.remove(Entity.RemovalReason.DISCARDED);
    }

    protected abstract void defineSynchedData(SynchedEntityData.Builder builder);

    public SynchedEntityData getEntityData() {
        return this.entityData;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Entity && ((Entity)other).id == this.id;
    }

    @Override
    public int hashCode() {
        return this.id;
    }

    public void remove(Entity.RemovalReason reason) {
        this.setRemoved(reason);
    }

    public void onClientRemoval() {
    }

    public void onRemoval(Entity.RemovalReason reason) {
    }

    public void setPose(Pose pose) {
        this.entityData.set(DATA_POSE, pose);
    }

    public Pose getPose() {
        return this.entityData.get(DATA_POSE);
    }

    public boolean hasPose(Pose pose) {
        return this.getPose() == pose;
    }

    public boolean closerThan(Entity entity, double distance) {
        return this.position().closerThan(entity.position(), distance);
    }

    public boolean closerThan(Entity entity, double horizontalDistance, double verticalDistance) {
        double d = entity.getX() - this.getX();
        double d1 = entity.getY() - this.getY();
        double d2 = entity.getZ() - this.getZ();
        return Mth.lengthSquared(d, d2) < Mth.square(horizontalDistance) && Mth.square(d1) < Mth.square(verticalDistance);
    }

    public void setRot(float yRot, float xRot) {
        this.setYRot(yRot % 360.0F);
        this.setXRot(xRot % 360.0F);
    }

    public final void setPos(Vec3 pos) {
        this.setPos(pos.x(), pos.y(), pos.z());
    }

    public void setPos(double x, double y, double z) {
        this.setPosRaw(x, y, z);
        this.setBoundingBox(this.makeBoundingBox());
    }

    protected final AABB makeBoundingBox() {
        return this.makeBoundingBox(this.position);
    }

    protected AABB makeBoundingBox(Vec3 position) {
        return this.dimensions.makeBoundingBox(position);
    }

    protected void reapplyPosition() {
        this.lastKnownPosition = null;
        this.setPos(this.position.x, this.position.y, this.position.z);
    }

    public void turn(double yRot, double xRot) {
        float f = (float)xRot * 0.15F;
        float f1 = (float)yRot * 0.15F;
        this.setXRot(this.getXRot() + f);
        this.setYRot(this.getYRot() + f1);
        this.setXRot(Mth.clamp(this.getXRot(), -90.0F, 90.0F));
        this.xRotO += f;
        this.yRotO += f1;
        this.xRotO = Mth.clamp(this.xRotO, -90.0F, 90.0F);
        if (this.vehicle != null) {
            this.vehicle.onPassengerTurned(this);
        }
    }

    public void updateDataBeforeSync() {
    }

    public void tick() {
        this.baseTick();
    }

    public void baseTick() {
        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("entityBaseTick");
        this.computeSpeed();
        this.inBlockState = null;
        if (this.isPassenger() && this.getVehicle().isRemoved()) {
            this.stopRiding();
        }

        if (this.boardingCooldown > 0) {
            this.boardingCooldown--;
        }

        this.handlePortal();
        if (this.canSpawnSprintParticle()) {
            this.spawnSprintParticle();
        }

        this.wasInPowderSnow = this.isInPowderSnow;
        this.isInPowderSnow = false;
        this.updateInWaterStateAndDoFluidPushing();
        this.updateFluidOnEyes();
        this.updateSwimming();
        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.remainingFireTicks > 0) {
                if (this.fireImmune()) {
                    this.clearFire();
                } else {
                    if (this.remainingFireTicks % 20 == 0 && !this.isInLava()) {
                        this.hurtServer(serverLevel, this.damageSources().onFire(), 1.0F);
                    }

                    this.setRemainingFireTicks(this.remainingFireTicks - 1);
                }
            }
        } else {
            this.clearFire();
        }

        if (this.isInLava()) {
            this.fallDistance *= 0.5;
        }

        this.checkBelowWorld();
        if (!this.level().isClientSide()) {
            this.setSharedFlagOnFire(this.remainingFireTicks > 0);
        }

        this.firstTick = false;
        if (this.level() instanceof ServerLevel serverLevelx && this instanceof Leashable) {
            Leashable.tickLeash(serverLevelx, (Entity & Leashable)this);
        }

        profilerFiller.pop();
    }

    protected void computeSpeed() {
        if (this.lastKnownPosition == null) {
            this.lastKnownPosition = this.position();
        }

        this.lastKnownSpeed = this.position().subtract(this.lastKnownPosition);
        this.lastKnownPosition = this.position();
    }

    public void setSharedFlagOnFire(boolean isOnFire) {
        this.setSharedFlag(FLAG_ONFIRE, isOnFire || this.hasVisualFire);
    }

    public void checkBelowWorld() {
        if (this.getY() < this.level().getMinY() - 64) {
            this.onBelowWorld();
        }
    }

    public void setPortalCooldown() {
        this.portalCooldown = this.getDimensionChangingDelay();
    }

    public void setPortalCooldown(int portalCooldown) {
        this.portalCooldown = portalCooldown;
    }

    public int getPortalCooldown() {
        return this.portalCooldown;
    }

    public boolean isOnPortalCooldown() {
        return this.portalCooldown > 0;
    }

    protected void processPortalCooldown() {
        if (this.isOnPortalCooldown()) {
            this.portalCooldown--;
        }
    }

    public void lavaIgnite() {
        if (!this.fireImmune()) {
            this.igniteForSeconds(15.0F);
        }
    }

    public void lavaHurt() {
        if (!this.fireImmune()) {
            if (this.level() instanceof ServerLevel serverLevel
                && this.hurtServer(serverLevel, this.damageSources().lava(), 4.0F)
                && this.shouldPlayLavaHurtSound()
                && !this.isSilent()) {
                serverLevel.playSound(
                    null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_BURN, this.getSoundSource(), 0.4F, 2.0F + this.random.nextFloat() * 0.4F
                );
            }
        }
    }

    protected boolean shouldPlayLavaHurtSound() {
        return true;
    }

    public final void igniteForSeconds(float seconds) {
        this.igniteForTicks(Mth.floor(seconds * 20.0F));
    }

    public void igniteForTicks(int ticks) {
        if (this.remainingFireTicks < ticks) {
            this.setRemainingFireTicks(ticks);
        }

        this.clearFreeze();
    }

    public void setRemainingFireTicks(int remainingFireTicks) {
        this.remainingFireTicks = remainingFireTicks;
    }

    public int getRemainingFireTicks() {
        return this.remainingFireTicks;
    }

    public void clearFire() {
        this.setRemainingFireTicks(Math.min(0, this.getRemainingFireTicks()));
    }

    protected void onBelowWorld() {
        this.discard();
    }

    public boolean isFree(double x, double y, double z) {
        return this.isFree(this.getBoundingBox().move(x, y, z));
    }

    private boolean isFree(AABB box) {
        return this.level().noCollision(this, box) && !this.level().containsAnyLiquid(box);
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
        this.checkSupportingBlock(onGround, null);
    }

    public void setOnGroundWithMovement(boolean onGround, Vec3 movement) {
        this.setOnGroundWithMovement(onGround, this.horizontalCollision, movement);
    }

    public void setOnGroundWithMovement(boolean onGround, boolean horizontalCollision, Vec3 movement) {
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
        this.checkSupportingBlock(onGround, movement);
    }

    public boolean isSupportedBy(BlockPos pos) {
        return this.mainSupportingBlockPos.isPresent() && this.mainSupportingBlockPos.get().equals(pos);
    }

    protected void checkSupportingBlock(boolean onGround, @Nullable Vec3 movement) {
        if (onGround) {
            AABB boundingBox = this.getBoundingBox();
            AABB aabb = new AABB(boundingBox.minX, boundingBox.minY - 1.0E-6, boundingBox.minZ, boundingBox.maxX, boundingBox.minY, boundingBox.maxZ);
            Optional<BlockPos> optional = this.level.findSupportingBlock(this, aabb);
            if (optional.isPresent() || this.onGroundNoBlocks) {
                this.mainSupportingBlockPos = optional;
            } else if (movement != null) {
                AABB aabb1 = aabb.move(-movement.x, 0.0, -movement.z);
                optional = this.level.findSupportingBlock(this, aabb1);
                this.mainSupportingBlockPos = optional;
            }

            this.onGroundNoBlocks = optional.isEmpty();
        } else {
            this.onGroundNoBlocks = false;
            if (this.mainSupportingBlockPos.isPresent()) {
                this.mainSupportingBlockPos = Optional.empty();
            }
        }
    }

    public boolean onGround() {
        return this.onGround;
    }

    public void move(MoverType type, Vec3 movement) {
        if (this.noPhysics) {
            this.setPos(this.getX() + movement.x, this.getY() + movement.y, this.getZ() + movement.z);
            this.horizontalCollision = false;
            this.verticalCollision = false;
            this.verticalCollisionBelow = false;
            this.minorHorizontalCollision = false;
        } else {
            if (type == MoverType.PISTON) {
                movement = this.limitPistonMovement(movement);
                if (movement.equals(Vec3.ZERO)) {
                    return;
                }
            }

            ProfilerFiller profilerFiller = Profiler.get();
            profilerFiller.push("move");
            if (this.stuckSpeedMultiplier.lengthSqr() > 1.0E-7) {
                if (type != MoverType.PISTON) {
                    movement = movement.multiply(this.stuckSpeedMultiplier);
                }

                this.stuckSpeedMultiplier = Vec3.ZERO;
                this.setDeltaMovement(Vec3.ZERO);
            }

            movement = this.maybeBackOffFromEdge(movement, type);
            Vec3 vec3 = this.collide(movement);
            double d = vec3.lengthSqr();
            if (d > 1.0E-7 || movement.lengthSqr() - d < 1.0E-7) {
                if (this.fallDistance != 0.0 && d >= 1.0) {
                    double min = Math.min(vec3.length(), 8.0);
                    Vec3 vec31 = this.position().add(vec3.normalize().scale(min));
                    BlockHitResult blockHitResult = this.level()
                        .clip(new ClipContext(this.position(), vec31, ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER, this));
                    if (blockHitResult.getType() != HitResult.Type.MISS) {
                        this.resetFallDistance();
                    }
                }

                Vec3 vec32 = this.position();
                Vec3 vec33 = vec32.add(vec3);
                this.addMovementThisTick(new Entity.Movement(vec32, vec33, movement));
                this.setPos(vec33);
            }

            profilerFiller.pop();
            profilerFiller.push("rest");
            boolean flag = !Mth.equal(movement.x, vec3.x);
            boolean flag1 = !Mth.equal(movement.z, vec3.z);
            this.horizontalCollision = flag || flag1;
            if (Math.abs(movement.y) > 0.0 || this.isLocalInstanceAuthoritative()) {
                this.verticalCollision = movement.y != vec3.y;
                this.verticalCollisionBelow = this.verticalCollision && movement.y < 0.0;
                this.setOnGroundWithMovement(this.verticalCollisionBelow, this.horizontalCollision, vec3);
            }

            if (this.horizontalCollision) {
                this.minorHorizontalCollision = this.isHorizontalCollisionMinor(vec3);
            } else {
                this.minorHorizontalCollision = false;
            }

            BlockPos onPosLegacy = this.getOnPosLegacy();
            BlockState blockState = this.level().getBlockState(onPosLegacy);
            if (this.isLocalInstanceAuthoritative()) {
                this.checkFallDamage(vec3.y, this.onGround(), blockState, onPosLegacy);
            }

            if (this.isRemoved()) {
                profilerFiller.pop();
            } else {
                if (this.horizontalCollision) {
                    Vec3 deltaMovement = this.getDeltaMovement();
                    this.setDeltaMovement(flag ? 0.0 : deltaMovement.x, deltaMovement.y, flag1 ? 0.0 : deltaMovement.z);
                }

                if (this.canSimulateMovement()) {
                    Block block = blockState.getBlock();
                    if (movement.y != vec3.y) {
                        block.updateEntityMovementAfterFallOn(this.level(), this);
                    }
                }

                if (!this.level().isClientSide() || this.isLocalInstanceAuthoritative()) {
                    Entity.MovementEmission movementEmission = this.getMovementEmission();
                    if (movementEmission.emitsAnything() && !this.isPassenger()) {
                        this.applyMovementEmissionAndPlaySound(movementEmission, vec3, onPosLegacy, blockState);
                    }
                }

                float blockSpeedFactor = this.getBlockSpeedFactor();
                this.setDeltaMovement(this.getDeltaMovement().multiply(blockSpeedFactor, 1.0, blockSpeedFactor));
                profilerFiller.pop();
            }
        }
    }

    private void applyMovementEmissionAndPlaySound(Entity.MovementEmission movementEmission, Vec3 movement, BlockPos pos, BlockState state) {
        float f = 0.6F;
        float f1 = (float)(movement.length() * 0.6F);
        float f2 = (float)(movement.horizontalDistance() * 0.6F);
        BlockPos onPos = this.getOnPos();
        BlockState blockState = this.level().getBlockState(onPos);
        boolean isStateClimbable = this.isStateClimbable(blockState);
        this.moveDist += isStateClimbable ? f1 : f2;
        this.flyDist += f1;
        if (this.moveDist > this.nextStep && !blockState.isAir()) {
            boolean flag = onPos.equals(pos);
            boolean flag1 = this.vibrationAndSoundEffectsFromBlock(pos, state, movementEmission.emitsSounds(), flag, movement);
            if (!flag) {
                flag1 |= this.vibrationAndSoundEffectsFromBlock(onPos, blockState, false, movementEmission.emitsEvents(), movement);
            }

            if (flag1) {
                this.nextStep = this.nextStep();
            } else if (this.isInWater()) {
                this.nextStep = this.nextStep();
                if (movementEmission.emitsSounds()) {
                    this.waterSwimSound();
                }

                if (movementEmission.emitsEvents()) {
                    this.gameEvent(GameEvent.SWIM);
                }
            }
        } else if (blockState.isAir()) {
            this.processFlappingMovement();
        }
    }

    protected void applyEffectsFromBlocks() {
        this.finalMovementsThisTick.clear();
        this.finalMovementsThisTick.addAll(this.movementThisTick);
        this.movementThisTick.clear();
        if (this.finalMovementsThisTick.isEmpty()) {
            this.finalMovementsThisTick.add(new Entity.Movement(this.oldPosition(), this.position()));
        } else if (this.finalMovementsThisTick.getLast().to.distanceToSqr(this.position()) > 9.9999994E-11F) {
            this.finalMovementsThisTick.add(new Entity.Movement(this.finalMovementsThisTick.getLast().to, this.position()));
        }

        this.applyEffectsFromBlocks(this.finalMovementsThisTick);
    }

    private void addMovementThisTick(Entity.Movement movement) {
        if (this.movementThisTick.size() >= 100) {
            Entity.Movement movement1 = this.movementThisTick.removeFirst();
            Entity.Movement movement2 = this.movementThisTick.removeFirst();
            Entity.Movement movement3 = new Entity.Movement(movement1.from(), movement2.to());
            this.movementThisTick.addFirst(movement3);
        }

        this.movementThisTick.add(movement);
    }

    public void removeLatestMovementRecording() {
        if (!this.movementThisTick.isEmpty()) {
            this.movementThisTick.removeLast();
        }
    }

    protected void clearMovementThisTick() {
        this.movementThisTick.clear();
    }

    public boolean hasMovedHorizontallyRecently() {
        return Math.abs(this.lastKnownSpeed.horizontalDistance()) > 1.0E-5F;
    }

    public void applyEffectsFromBlocks(Vec3 oldPosition, Vec3 position) {
        this.applyEffectsFromBlocks(List.of(new Entity.Movement(oldPosition, position)));
    }

    private void applyEffectsFromBlocks(List<Entity.Movement> movements) {
        if (this.isAffectedByBlocks()) {
            if (this.onGround()) {
                BlockPos onPosLegacy = this.getOnPosLegacy();
                BlockState blockState = this.level().getBlockState(onPosLegacy);
                blockState.getBlock().stepOn(this.level(), onPosLegacy, blockState, this);
            }

            boolean isOnFire = this.isOnFire();
            boolean isFreezing = this.isFreezing();
            int remainingFireTicks = this.getRemainingFireTicks();
            this.checkInsideBlocks(movements, this.insideEffectCollector);
            this.insideEffectCollector.applyAndClear(this);
            if (this.isInRain()) {
                this.clearFire();
            }

            if (isOnFire && !this.isOnFire() || isFreezing && !this.isFreezing()) {
                this.playEntityOnFireExtinguishedSound();
            }

            boolean flag = this.getRemainingFireTicks() > remainingFireTicks;
            if (!this.level().isClientSide() && !this.isOnFire() && !flag) {
                this.setRemainingFireTicks(-this.getFireImmuneTicks());
            }
        }
    }

    public boolean isAffectedByBlocks() {
        return !this.isRemoved() && !this.noPhysics;
    }

    private boolean isStateClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(Blocks.POWDER_SNOW);
    }

    private boolean vibrationAndSoundEffectsFromBlock(BlockPos pos, BlockState state, boolean playStepSound, boolean broadcastGameEvent, Vec3 entityPos) {
        if (state.isAir()) {
            return false;
        } else {
            boolean isStateClimbable = this.isStateClimbable(state);
            if ((this.onGround() || isStateClimbable || this.isCrouching() && entityPos.y == 0.0 || this.isOnRails()) && !this.isSwimming()) {
                if (playStepSound) {
                    this.walkingStepSound(pos, state);
                }

                if (broadcastGameEvent) {
                    this.level().gameEvent(GameEvent.STEP, this.position(), GameEvent.Context.of(this, state));
                }

                return true;
            } else {
                return false;
            }
        }
    }

    protected boolean isHorizontalCollisionMinor(Vec3 deltaMovement) {
        return false;
    }

    protected void playEntityOnFireExtinguishedSound() {
        if (!this.level.isClientSide()) {
            this.level()
                .playSound(
                    null,
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    SoundEvents.GENERIC_EXTINGUISH_FIRE,
                    this.getSoundSource(),
                    0.7F,
                    1.6F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F
                );
        }
    }

    public void extinguishFire() {
        if (this.isOnFire()) {
            this.playEntityOnFireExtinguishedSound();
        }

        this.clearFire();
    }

    protected void processFlappingMovement() {
        if (this.isFlapping()) {
            this.onFlap();
            if (this.getMovementEmission().emitsEvents()) {
                this.gameEvent(GameEvent.FLAP);
            }
        }
    }

    @Deprecated
    public BlockPos getOnPosLegacy() {
        return this.getOnPos(0.2F);
    }

    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.500001F);
    }

    public BlockPos getOnPos() {
        return this.getOnPos(1.0E-5F);
    }

    protected BlockPos getOnPos(float yOffset) {
        if (this.mainSupportingBlockPos.isPresent()) {
            BlockPos blockPos = this.mainSupportingBlockPos.get();
            if (!(yOffset > 1.0E-5F)) {
                return blockPos;
            } else {
                BlockState blockState = this.level().getBlockState(blockPos);
                return (!(yOffset <= 0.5) || !blockState.is(BlockTags.FENCES))
                        && !blockState.is(BlockTags.WALLS)
                        && !(blockState.getBlock() instanceof FenceGateBlock)
                    ? blockPos.atY(Mth.floor(this.position.y - yOffset))
                    : blockPos;
            }
        } else {
            int floor = Mth.floor(this.position.x);
            int floor1 = Mth.floor(this.position.y - yOffset);
            int floor2 = Mth.floor(this.position.z);
            return new BlockPos(floor, floor1, floor2);
        }
    }

    protected float getBlockJumpFactor() {
        float jumpFactor = this.level().getBlockState(this.blockPosition()).getBlock().getJumpFactor();
        float jumpFactor1 = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getJumpFactor();
        return jumpFactor == 1.0 ? jumpFactor1 : jumpFactor;
    }

    protected float getBlockSpeedFactor() {
        BlockState blockState = this.level().getBlockState(this.blockPosition());
        float speedFactor = blockState.getBlock().getSpeedFactor();
        if (!blockState.is(Blocks.WATER) && !blockState.is(Blocks.BUBBLE_COLUMN)) {
            return speedFactor == 1.0 ? this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getSpeedFactor() : speedFactor;
        } else {
            return speedFactor;
        }
    }

    protected Vec3 maybeBackOffFromEdge(Vec3 movement, MoverType type) {
        return movement;
    }

    protected Vec3 limitPistonMovement(Vec3 pos) {
        if (pos.lengthSqr() <= 1.0E-7) {
            return pos;
        } else {
            long gameTime = this.level().getGameTime();
            if (gameTime != this.pistonDeltasGameTime) {
                Arrays.fill(this.pistonDeltas, 0.0);
                this.pistonDeltasGameTime = gameTime;
            }

            if (pos.x != 0.0) {
                double d = this.applyPistonMovementRestriction(Direction.Axis.X, pos.x);
                return Math.abs(d) <= 1.0E-5F ? Vec3.ZERO : new Vec3(d, 0.0, 0.0);
            } else if (pos.y != 0.0) {
                double d = this.applyPistonMovementRestriction(Direction.Axis.Y, pos.y);
                return Math.abs(d) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, d, 0.0);
            } else if (pos.z != 0.0) {
                double d = this.applyPistonMovementRestriction(Direction.Axis.Z, pos.z);
                return Math.abs(d) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, 0.0, d);
            } else {
                return Vec3.ZERO;
            }
        }
    }

    private double applyPistonMovementRestriction(Direction.Axis axis, double distance) {
        int ordinal = axis.ordinal();
        double d = Mth.clamp(distance + this.pistonDeltas[ordinal], -0.51, 0.51);
        distance = d - this.pistonDeltas[ordinal];
        this.pistonDeltas[ordinal] = d;
        return distance;
    }

    public double getAvailableSpaceBelow(double distance) {
        AABB boundingBox = this.getBoundingBox();
        AABB aabb = boundingBox.setMinY(boundingBox.minY - distance).setMaxY(boundingBox.minY);
        List<VoxelShape> list = collectAllColliders(this, this.level, aabb);
        return list.isEmpty() ? distance : -Shapes.collide(Direction.Axis.Y, boundingBox, list, -distance);
    }

    private Vec3 collide(Vec3 vec) {
        AABB boundingBox = this.getBoundingBox();
        List<VoxelShape> entityCollisions = this.level().getEntityCollisions(this, boundingBox.expandTowards(vec));
        Vec3 vec3 = vec.lengthSqr() == 0.0 ? vec : collideBoundingBox(this, vec, boundingBox, this.level(), entityCollisions);
        boolean flag = vec.x != vec3.x;
        boolean flag1 = vec.y != vec3.y;
        boolean flag2 = vec.z != vec3.z;
        boolean flag3 = flag1 && vec.y < 0.0;
        if (this.maxUpStep() > 0.0F && (flag3 || this.onGround()) && (flag || flag2)) {
            AABB aabb = flag3 ? boundingBox.move(0.0, vec3.y, 0.0) : boundingBox;
            AABB aabb1 = aabb.expandTowards(vec.x, this.maxUpStep(), vec.z);
            if (!flag3) {
                aabb1 = aabb1.expandTowards(0.0, -1.0E-5F, 0.0);
            }

            List<VoxelShape> list = collectColliders(this, this.level, entityCollisions, aabb1);
            float f = (float)vec3.y;
            float[] floats = collectCandidateStepUpHeights(aabb, list, this.maxUpStep(), f);

            for (float f1 : floats) {
                Vec3 vec31 = collideWithShapes(new Vec3(vec.x, f1, vec.z), aabb, list);
                if (vec31.horizontalDistanceSqr() > vec3.horizontalDistanceSqr()) {
                    double d = boundingBox.minY - aabb.minY;
                    return vec31.subtract(0.0, d, 0.0);
                }
            }
        }

        return vec3;
    }

    private static float[] collectCandidateStepUpHeights(AABB box, List<VoxelShape> colliders, float deltaY, float maxUpStep) {
        FloatSet set = new FloatArraySet(4);

        for (VoxelShape voxelShape : colliders) {
            for (double d : voxelShape.getCoords(Direction.Axis.Y)) {
                float f = (float)(d - box.minY);
                if (!(f < 0.0F) && f != maxUpStep) {
                    if (f > deltaY) {
                        break;
                    }

                    set.add(f);
                }
            }
        }

        float[] floats = set.toFloatArray();
        FloatArrays.unstableSort(floats);
        return floats;
    }

    public static Vec3 collideBoundingBox(@Nullable Entity entity, Vec3 vec, AABB collisionBox, Level level, List<VoxelShape> potentialHits) {
        List<VoxelShape> list = collectColliders(entity, level, potentialHits, collisionBox.expandTowards(vec));
        return collideWithShapes(vec, collisionBox, list);
    }

    public static List<VoxelShape> collectAllColliders(@Nullable Entity entity, Level level, AABB boundingBox) {
        List<VoxelShape> entityCollisions = level.getEntityCollisions(entity, boundingBox);
        return collectColliders(entity, level, entityCollisions, boundingBox);
    }

    private static List<VoxelShape> collectColliders(@Nullable Entity entity, Level level, List<VoxelShape> collisions, AABB boundingBox) {
        Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(collisions.size() + 1);
        if (!collisions.isEmpty()) {
            builder.addAll(collisions);
        }

        WorldBorder worldBorder = level.getWorldBorder();
        boolean flag = entity != null && worldBorder.isInsideCloseToBorder(entity, boundingBox);
        if (flag) {
            builder.add(worldBorder.getCollisionShape());
        }

        builder.addAll(level.getBlockCollisions(entity, boundingBox));
        return builder.build();
    }

    private static Vec3 collideWithShapes(Vec3 deltaMovement, AABB entityBB, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return deltaMovement;
        } else {
            Vec3 vec3 = Vec3.ZERO;

            for (Direction.Axis axis : Direction.axisStepOrder(deltaMovement)) {
                double d = deltaMovement.get(axis);
                if (d != 0.0) {
                    double d1 = Shapes.collide(axis, entityBB.move(vec3), shapes, d);
                    vec3 = vec3.with(axis, d1);
                }
            }

            return vec3;
        }
    }

    protected float nextStep() {
        return (int)this.moveDist + 1;
    }

    public SoundEvent getSwimSound() {
        return SoundEvents.GENERIC_SWIM;
    }

    public SoundEvent getSwimSplashSound() {
        return SoundEvents.GENERIC_SPLASH;
    }

    public SoundEvent getSwimHighSpeedSplashSound() {
        return SoundEvents.GENERIC_SPLASH;
    }

    private void checkInsideBlocks(List<Entity.Movement> movements, InsideBlockEffectApplier.StepBasedCollector stepBasedCollector) {
        if (this.isAffectedByBlocks()) {
            LongSet set = this.visitedBlocks;

            for (Entity.Movement movement : movements) {
                Vec3 vec3 = movement.from;
                Vec3 vec31 = movement.to().subtract(movement.from());
                int i = 16;
                if (movement.axisDependentOriginalMovement().isPresent() && vec31.lengthSqr() > 0.0) {
                    for (Direction.Axis axis : Direction.axisStepOrder(movement.axisDependentOriginalMovement().get())) {
                        double d = vec31.get(axis);
                        if (d != 0.0) {
                            Vec3 vec32 = vec3.relative(axis.getPositive(), d);
                            i -= this.checkInsideBlocks(vec3, vec32, stepBasedCollector, set, i);
                            vec3 = vec32;
                        }
                    }
                } else {
                    i -= this.checkInsideBlocks(movement.from(), movement.to(), stepBasedCollector, set, 16);
                }

                if (i <= 0) {
                    this.checkInsideBlocks(movement.to(), movement.to(), stepBasedCollector, set, 1);
                }
            }

            set.clear();
        }
    }

    private int checkInsideBlocks(Vec3 from, Vec3 to, InsideBlockEffectApplier.StepBasedCollector stepBasedCollector, LongSet visited, int maxSteps) {
        AABB aabb = this.makeBoundingBox(to).deflate(1.0E-5F);
        boolean flag = from.distanceToSqr(to) > Mth.square(0.9999900000002526);
        boolean flag1 = this.level instanceof ServerLevel serverLevel
            && serverLevel.getServer().debugSubscribers().hasAnySubscriberFor(DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS);
        AtomicInteger atomicInteger = new AtomicInteger();
        BlockGetter.forEachBlockIntersectedBetween(
            from,
            to,
            aabb,
            (pos, index) -> {
                if (!this.isAlive()) {
                    return false;
                } else if (index >= maxSteps) {
                    return false;
                } else {
                    atomicInteger.set(index);
                    BlockState blockState = this.level().getBlockState(pos);
                    if (blockState.isAir()) {
                        if (flag1) {
                            this.debugBlockIntersection((ServerLevel)this.level(), pos.immutable(), false, false);
                        }

                        return true;
                    } else {
                        VoxelShape entityInsideCollisionShape = blockState.getEntityInsideCollisionShape(this.level(), pos, this);
                        boolean flag2 = entityInsideCollisionShape == Shapes.block()
                            || this.collidedWithShapeMovingFrom(from, to, entityInsideCollisionShape.move(new Vec3(pos)).toAabbs());
                        boolean flag3 = this.collidedWithFluid(blockState.getFluidState(), pos, from, to);
                        if ((flag2 || flag3) && visited.add(pos.asLong())) {
                            if (flag2) {
                                try {
                                    boolean flag4 = flag || aabb.intersects(pos);
                                    stepBasedCollector.advanceStep(index);
                                    blockState.entityInside(this.level(), pos, this, stepBasedCollector, flag4);
                                    this.onInsideBlock(blockState);
                                } catch (Throwable var20) {
                                    CrashReport crashReport = CrashReport.forThrowable(var20, "Colliding entity with block");
                                    CrashReportCategory crashReportCategory = crashReport.addCategory("Block being collided with");
                                    CrashReportCategory.populateBlockDetails(crashReportCategory, this.level(), pos, blockState);
                                    CrashReportCategory crashReportCategory1 = crashReport.addCategory("Entity being checked for collision");
                                    this.fillCrashReportCategory(crashReportCategory1);
                                    throw new ReportedException(crashReport);
                                }
                            }

                            if (flag3) {
                                stepBasedCollector.advanceStep(index);
                                blockState.getFluidState().entityInside(this.level(), pos, this, stepBasedCollector);
                            }

                            if (flag1) {
                                this.debugBlockIntersection((ServerLevel)this.level(), pos.immutable(), flag2, flag3);
                            }

                            return true;
                        } else {
                            return true;
                        }
                    }
                }
            }
        );
        return atomicInteger.get() + 1;
    }

    private void debugBlockIntersection(ServerLevel level, BlockPos pos, boolean inBlock, boolean inFluid) {
        DebugEntityBlockIntersection debugEntityBlockIntersection;
        if (inFluid) {
            debugEntityBlockIntersection = DebugEntityBlockIntersection.IN_FLUID;
        } else if (inBlock) {
            debugEntityBlockIntersection = DebugEntityBlockIntersection.IN_BLOCK;
        } else {
            debugEntityBlockIntersection = DebugEntityBlockIntersection.IN_AIR;
        }

        level.debugSynchronizers().sendBlockValue(pos, DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS, debugEntityBlockIntersection);
    }

    public boolean collidedWithFluid(FluidState fluid, BlockPos pos, Vec3 from, Vec3 to) {
        AABB aabb = fluid.getAABB(this.level(), pos);
        return aabb != null && this.collidedWithShapeMovingFrom(from, to, List.of(aabb));
    }

    public boolean collidedWithShapeMovingFrom(Vec3 from, Vec3 to, List<AABB> boxes) {
        AABB aabb = this.makeBoundingBox(from);
        Vec3 vec3 = to.subtract(from);
        return aabb.collidedAlongVector(vec3, boxes);
    }

    protected void onInsideBlock(BlockState state) {
    }

    public BlockPos adjustSpawnLocation(ServerLevel level, BlockPos pos) {
        BlockPos blockPos = level.getRespawnData().pos();
        Vec3 center = blockPos.getCenter();
        int i = level.getChunkAt(blockPos).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockPos.getX(), blockPos.getZ()) + 1;
        return BlockPos.containing(center.x, i, center.z);
    }

    public void gameEvent(Holder<GameEvent> gameEvent, @Nullable Entity entity) {
        this.level().gameEvent(entity, gameEvent, this.position);
    }

    public void gameEvent(Holder<GameEvent> gameEvent) {
        this.gameEvent(gameEvent, this);
    }

    private void walkingStepSound(BlockPos pos, BlockState state) {
        this.playStepSound(pos, state);
        if (this.shouldPlayAmethystStepSound(state)) {
            this.playAmethystStepSound();
        }
    }

    protected void waterSwimSound() {
        Entity entity = Objects.requireNonNullElse(this.getControllingPassenger(), this);
        float f = entity == this ? 0.35F : 0.4F;
        Vec3 deltaMovement = entity.getDeltaMovement();
        float min = Math.min(
            1.0F, (float)Math.sqrt(deltaMovement.x * deltaMovement.x * 0.2F + deltaMovement.y * deltaMovement.y + deltaMovement.z * deltaMovement.z * 0.2F) * f
        );
        this.playSwimSound(min);
    }

    protected BlockPos getPrimaryStepSoundBlockPos(BlockPos pos) {
        BlockPos blockPos = pos.above();
        BlockState blockState = this.level().getBlockState(blockPos);
        return !blockState.is(BlockTags.INSIDE_STEP_SOUND_BLOCKS) && !blockState.is(BlockTags.COMBINATION_STEP_SOUND_BLOCKS) ? pos : blockPos;
    }

    protected void playCombinationStepSounds(BlockState primaryState, BlockState secondaryState) {
        SoundType soundType = primaryState.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
        this.playMuffledStepSound(secondaryState);
    }

    protected void playMuffledStepSound(BlockState state) {
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.05F, soundType.getPitch() * 0.8F);
    }

    protected void playStepSound(BlockPos pos, BlockState state) {
        SoundType soundType = state.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
    }

    private boolean shouldPlayAmethystStepSound(BlockState state) {
        return state.is(BlockTags.CRYSTAL_SOUND_BLOCKS) && this.tickCount >= this.lastCrystalSoundPlayTick + 20;
    }

    private void playAmethystStepSound() {
        this.crystalSoundIntensity = this.crystalSoundIntensity * (float)Math.pow(0.997, this.tickCount - this.lastCrystalSoundPlayTick);
        this.crystalSoundIntensity = Math.min(1.0F, this.crystalSoundIntensity + 0.07F);
        float f = 0.5F + this.crystalSoundIntensity * this.random.nextFloat() * 1.2F;
        float f1 = 0.1F + this.crystalSoundIntensity * 1.2F;
        this.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, f1, f);
        this.lastCrystalSoundPlayTick = this.tickCount;
    }

    protected void playSwimSound(float volume) {
        this.playSound(this.getSwimSound(), volume, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
    }

    protected void onFlap() {
    }

    protected boolean isFlapping() {
        return false;
    }

    public void playSound(SoundEvent sound, float volume, float pitch) {
        if (!this.isSilent()) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(), sound, this.getSoundSource(), volume, pitch);
        }
    }

    public void playSound(SoundEvent sound) {
        if (!this.isSilent()) {
            this.playSound(sound, 1.0F, 1.0F);
        }
    }

    public boolean isSilent() {
        return this.entityData.get(DATA_SILENT);
    }

    public void setSilent(boolean isSilent) {
        this.entityData.set(DATA_SILENT, isSilent);
    }

    public boolean isNoGravity() {
        return this.entityData.get(DATA_NO_GRAVITY);
    }

    public void setNoGravity(boolean noGravity) {
        this.entityData.set(DATA_NO_GRAVITY, noGravity);
    }

    protected double getDefaultGravity() {
        return 0.0;
    }

    public final double getGravity() {
        return this.isNoGravity() ? 0.0 : this.getDefaultGravity();
    }

    protected void applyGravity() {
        double gravity = this.getGravity();
        if (gravity != 0.0) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0, -gravity, 0.0));
        }
    }

    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.ALL;
    }

    public boolean dampensVibrations() {
        return false;
    }

    public final void doCheckFallDamage(double x, double y, double z, boolean onGround) {
        if (!this.touchingUnloadedChunk()) {
            this.checkSupportingBlock(onGround, new Vec3(x, y, z));
            BlockPos onPosLegacy = this.getOnPosLegacy();
            BlockState blockState = this.level().getBlockState(onPosLegacy);
            this.checkFallDamage(y, onGround, blockState, onPosLegacy);
        }
    }

    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        if (!this.isInWater() && y < 0.0) {
            this.fallDistance -= (float)y;
        }

        if (onGround) {
            if (this.fallDistance > 0.0) {
                state.getBlock().fallOn(this.level(), state, pos, this, this.fallDistance);
                this.level()
                    .gameEvent(
                        GameEvent.HIT_GROUND,
                        this.position,
                        GameEvent.Context.of(
                            this, this.mainSupportingBlockPos.<BlockState>map(supportingPos -> this.level().getBlockState(supportingPos)).orElse(state)
                        )
                    );
            }

            this.resetFallDistance();
        }
    }

    public boolean fireImmune() {
        return this.getType().fireImmune();
    }

    public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource damageSource) {
        if (this.type.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            return false;
        } else {
            this.propagateFallToPassengers(fallDistance, damageMultiplier, damageSource);
            return false;
        }
    }

    protected void propagateFallToPassengers(double fallDistance, float damageMultiplier, DamageSource damageSource) {
        if (this.isVehicle()) {
            for (Entity entity : this.getPassengers()) {
                entity.causeFallDamage(fallDistance, damageMultiplier, damageSource);
            }
        }
    }

    public boolean isInWater() {
        return this.wasTouchingWater;
    }

    public boolean isInRain() {
        BlockPos blockPos = this.blockPosition();
        return this.level().isRainingAt(blockPos)
            || this.level().isRainingAt(BlockPos.containing(blockPos.getX(), this.getBoundingBox().maxY, blockPos.getZ()));
    }

    public boolean isInWaterOrRain() {
        return this.isInWater() || this.isInRain();
    }

    public boolean isInLiquid() {
        return this.isInWater() || this.isInLava();
    }

    public boolean isUnderWater() {
        return this.wasEyeInWater && this.isInWater();
    }

    public boolean isInShallowWater() {
        return this.isInWater() && !this.isUnderWater();
    }

    public boolean isInClouds() {
        if (ARGB.alpha(this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_COLOR, this.position())) == 0) {
            return false;
        } else {
            float value = this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_HEIGHT, this.position());
            if (this.getY() + this.getBbHeight() < value) {
                return false;
            } else {
                float f = value + 4.0F;
                return this.getY() <= f;
            }
        }
    }

    public void updateSwimming() {
        if (this.isSwimming()) {
            this.setSwimming(this.isSprinting() && this.isInWater() && !this.isPassenger());
        } else {
            this.setSwimming(
                this.isSprinting() && this.isUnderWater() && !this.isPassenger() && this.level().getFluidState(this.blockPosition).is(FluidTags.WATER)
            );
        }
    }

    protected boolean updateInWaterStateAndDoFluidPushing() {
        this.fluidHeight.clear();
        this.updateInWaterStateAndDoWaterCurrentPushing();
        double d = this.level.environmentAttributes().getDimensionValue(EnvironmentAttributes.FAST_LAVA) ? 0.007 : 0.0023333333333333335;
        boolean flag = this.updateFluidHeightAndDoFluidPushing(FluidTags.LAVA, d);
        return this.isInWater() || flag;
    }

    void updateInWaterStateAndDoWaterCurrentPushing() {
        if (this.getVehicle() instanceof AbstractBoat abstractBoat && !abstractBoat.isUnderWater()) {
            this.wasTouchingWater = false;
        } else if (this.updateFluidHeightAndDoFluidPushing(FluidTags.WATER, 0.014)) {
            if (!this.wasTouchingWater && !this.firstTick) {
                this.doWaterSplashEffect();
            }

            this.resetFallDistance();
            this.wasTouchingWater = true;
        } else {
            this.wasTouchingWater = false;
        }
    }

    private void updateFluidOnEyes() {
        this.wasEyeInWater = this.isEyeInFluid(FluidTags.WATER);
        this.fluidOnEyes.clear();
        double eyeY = this.getEyeY();
        if (!(
            this.getVehicle() instanceof AbstractBoat abstractBoat
                && !abstractBoat.isUnderWater()
                && abstractBoat.getBoundingBox().maxY >= eyeY
                && abstractBoat.getBoundingBox().minY <= eyeY
        )) {
            BlockPos blockPos = BlockPos.containing(this.getX(), eyeY, this.getZ());
            FluidState fluidState = this.level().getFluidState(blockPos);
            double d = blockPos.getY() + fluidState.getHeight(this.level(), blockPos);
            if (d > eyeY) {
                fluidState.getTags().forEach(this.fluidOnEyes::add);
            }
        }
    }

    protected void doWaterSplashEffect() {
        Entity entity = Objects.requireNonNullElse(this.getControllingPassenger(), this);
        float f = entity == this ? 0.2F : 0.9F;
        Vec3 deltaMovement = entity.getDeltaMovement();
        float min = Math.min(
            1.0F, (float)Math.sqrt(deltaMovement.x * deltaMovement.x * 0.2F + deltaMovement.y * deltaMovement.y + deltaMovement.z * deltaMovement.z * 0.2F) * f
        );
        if (min < 0.25F) {
            this.playSound(this.getSwimSplashSound(), min, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
        } else {
            this.playSound(this.getSwimHighSpeedSplashSound(), min, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
        }

        float f1 = Mth.floor(this.getY());

        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double d = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double d1 = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            this.level()
                .addParticle(
                    ParticleTypes.BUBBLE,
                    this.getX() + d,
                    f1 + 1.0F,
                    this.getZ() + d1,
                    deltaMovement.x,
                    deltaMovement.y - this.random.nextDouble() * 0.2F,
                    deltaMovement.z
                );
        }

        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double d = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double d1 = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            this.level().addParticle(ParticleTypes.SPLASH, this.getX() + d, f1 + 1.0F, this.getZ() + d1, deltaMovement.x, deltaMovement.y, deltaMovement.z);
        }

        this.gameEvent(GameEvent.SPLASH);
    }

    @Deprecated
    protected BlockState getBlockStateOnLegacy() {
        return this.level().getBlockState(this.getOnPosLegacy());
    }

    public BlockState getBlockStateOn() {
        return this.level().getBlockState(this.getOnPos());
    }

    public boolean canSpawnSprintParticle() {
        return this.isSprinting() && !this.isInWater() && !this.isSpectator() && !this.isCrouching() && !this.isInLava() && this.isAlive();
    }

    protected void spawnSprintParticle() {
        BlockPos onPosLegacy = this.getOnPosLegacy();
        BlockState blockState = this.level().getBlockState(onPosLegacy);
        if (blockState.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 deltaMovement = this.getDeltaMovement();
            BlockPos blockPos = this.blockPosition();
            double d = this.getX() + (this.random.nextDouble() - 0.5) * this.dimensions.width();
            double d1 = this.getZ() + (this.random.nextDouble() - 0.5) * this.dimensions.width();
            if (blockPos.getX() != onPosLegacy.getX()) {
                d = Mth.clamp(d, (double)onPosLegacy.getX(), onPosLegacy.getX() + 1.0);
            }

            if (blockPos.getZ() != onPosLegacy.getZ()) {
                d1 = Mth.clamp(d1, (double)onPosLegacy.getZ(), onPosLegacy.getZ() + 1.0);
            }

            this.level()
                .addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, blockState), d, this.getY() + 0.1, d1, deltaMovement.x * -4.0, 1.5, deltaMovement.z * -4.0
                );
        }
    }

    public boolean isEyeInFluid(TagKey<Fluid> fluidTag) {
        return this.fluidOnEyes.contains(fluidTag);
    }

    public boolean isInLava() {
        return !this.firstTick && this.fluidHeight.getDouble(FluidTags.LAVA) > 0.0;
    }

    public void moveRelative(float amount, Vec3 relative) {
        Vec3 inputVector = getInputVector(relative, amount, this.getYRot());
        this.setDeltaMovement(this.getDeltaMovement().add(inputVector));
    }

    protected static Vec3 getInputVector(Vec3 relative, float motionScaler, float facing) {
        double d = relative.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            Vec3 vec3 = (d > 1.0 ? relative.normalize() : relative).scale(motionScaler);
            float sin = Mth.sin(facing * (float) (Math.PI / 180.0));
            float cos = Mth.cos(facing * (float) (Math.PI / 180.0));
            return new Vec3(vec3.x * cos - vec3.z * sin, vec3.y, vec3.z * cos + vec3.x * sin);
        }
    }

    @Deprecated
    public float getLightLevelDependentMagicValue() {
        return this.level().hasChunkAt(this.getBlockX(), this.getBlockZ())
            ? this.level().getLightLevelDependentMagicValue(BlockPos.containing(this.getX(), this.getEyeY(), this.getZ()))
            : 0.0F;
    }

    public void absSnapTo(double x, double y, double z, float yRot, float xRot) {
        this.absSnapTo(x, y, z);
        this.absSnapRotationTo(yRot, xRot);
    }

    public void absSnapRotationTo(float yRot, float xRot) {
        this.setYRot(yRot % 360.0F);
        this.setXRot(Mth.clamp(xRot, -90.0F, 90.0F) % 360.0F);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public void absSnapTo(double x, double y, double z) {
        double d = Mth.clamp(x, -3.0E7, 3.0E7);
        double d1 = Mth.clamp(z, -3.0E7, 3.0E7);
        this.xo = d;
        this.yo = y;
        this.zo = d1;
        this.setPos(d, y, d1);
    }

    public void snapTo(Vec3 pos) {
        this.snapTo(pos.x, pos.y, pos.z);
    }

    public void snapTo(double x, double y, double z) {
        this.snapTo(x, y, z, this.getYRot(), this.getXRot());
    }

    public void snapTo(BlockPos pos, float yRot, float xRot) {
        this.snapTo(pos.getBottomCenter(), yRot, xRot);
    }

    public void snapTo(Vec3 pos, float yRot, float xRot) {
        this.snapTo(pos.x, pos.y, pos.z, yRot, xRot);
    }

    public void snapTo(double x, double y, double z, float yRot, float xRot) {
        this.setPosRaw(x, y, z);
        this.setYRot(yRot);
        this.setXRot(xRot);
        this.setOldPosAndRot();
        this.reapplyPosition();
    }

    public final void setOldPosAndRot() {
        this.setOldPos();
        this.setOldRot();
    }

    public final void setOldPosAndRot(Vec3 pos, float yRot, float xRot) {
        this.setOldPos(pos);
        this.setOldRot(yRot, xRot);
    }

    protected void setOldPos() {
        this.setOldPos(this.position);
    }

    public void setOldRot() {
        this.setOldRot(this.getYRot(), this.getXRot());
    }

    private void setOldPos(Vec3 pos) {
        this.xo = this.xOld = pos.x;
        this.yo = this.yOld = pos.y;
        this.zo = this.zOld = pos.z;
    }

    private void setOldRot(float yRot, float xRot) {
        this.yRotO = yRot;
        this.xRotO = xRot;
    }

    public final Vec3 oldPosition() {
        return new Vec3(this.xOld, this.yOld, this.zOld);
    }

    public float distanceTo(Entity entity) {
        float f = (float)(this.getX() - entity.getX());
        float f1 = (float)(this.getY() - entity.getY());
        float f2 = (float)(this.getZ() - entity.getZ());
        return Mth.sqrt(f * f + f1 * f1 + f2 * f2);
    }

    public double distanceToSqr(double x, double y, double z) {
        double d = this.getX() - x;
        double d1 = this.getY() - y;
        double d2 = this.getZ() - z;
        return d * d + d1 * d1 + d2 * d2;
    }

    public double distanceToSqr(Entity entity) {
        return this.distanceToSqr(entity.position());
    }

    public double distanceToSqr(Vec3 pos) {
        double d = this.getX() - pos.x;
        double d1 = this.getY() - pos.y;
        double d2 = this.getZ() - pos.z;
        return d * d + d1 * d1 + d2 * d2;
    }

    public void playerTouch(Player player) {
    }

    public void push(Entity entity) {
        if (!this.isPassengerOfSameVehicle(entity)) {
            if (!entity.noPhysics && !this.noPhysics) {
                double d = entity.getX() - this.getX();
                double d1 = entity.getZ() - this.getZ();
                double max = Mth.absMax(d, d1);
                if (max >= 0.01F) {
                    max = Math.sqrt(max);
                    d /= max;
                    d1 /= max;
                    double d2 = 1.0 / max;
                    if (d2 > 1.0) {
                        d2 = 1.0;
                    }

                    d *= d2;
                    d1 *= d2;
                    d *= 0.05F;
                    d1 *= 0.05F;
                    if (!this.isVehicle() && this.isPushable()) {
                        this.push(-d, 0.0, -d1);
                    }

                    if (!entity.isVehicle() && entity.isPushable()) {
                        entity.push(d, 0.0, d1);
                    }
                }
            }
        }
    }

    public void push(Vec3 vector) {
        if (vector.isFinite()) {
            this.push(vector.x, vector.y, vector.z);
        }
    }

    public void push(double x, double y, double z) {
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            this.setDeltaMovement(this.getDeltaMovement().add(x, y, z));
            this.needsSync = true;
        }
    }

    protected void markHurt() {
        this.hurtMarked = true;
    }

    @Deprecated
    public final void hurt(DamageSource damageSource, float amount) {
        if (this.level instanceof ServerLevel serverLevel) {
            this.hurtServer(serverLevel, damageSource, amount);
        }
    }

    @Deprecated
    public final boolean hurtOrSimulate(DamageSource damageSource, float amount) {
        return this.level instanceof ServerLevel serverLevel ? this.hurtServer(serverLevel, damageSource, amount) : this.hurtClient(damageSource);
    }

    public abstract boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount);

    public boolean hurtClient(DamageSource damageSource) {
        return false;
    }

    public final Vec3 getViewVector(float partialTick) {
        return this.calculateViewVector(this.getViewXRot(partialTick), this.getViewYRot(partialTick));
    }

    public Direction getNearestViewDirection() {
        return Direction.getApproximateNearest(this.getViewVector(1.0F));
    }

    public float getViewXRot(float partialTick) {
        return this.getXRot(partialTick);
    }

    public float getViewYRot(float partialTick) {
        return this.getYRot(partialTick);
    }

    public float getXRot(float partialTick) {
        return partialTick == 1.0F ? this.getXRot() : Mth.lerp(partialTick, this.xRotO, this.getXRot());
    }

    public float getYRot(float partialTick) {
        return partialTick == 1.0F ? this.getYRot() : Mth.rotLerp(partialTick, this.yRotO, this.getYRot());
    }

    public final Vec3 calculateViewVector(float xRot, float yRot) {
        float f = xRot * (float) (Math.PI / 180.0);
        float f1 = -yRot * (float) (Math.PI / 180.0);
        float cos = Mth.cos(f1);
        float sin = Mth.sin(f1);
        float cos1 = Mth.cos(f);
        float sin1 = Mth.sin(f);
        return new Vec3(sin * cos1, -sin1, cos * cos1);
    }

    public final Vec3 getUpVector(float partialTick) {
        return this.calculateUpVector(this.getViewXRot(partialTick), this.getViewYRot(partialTick));
    }

    protected final Vec3 calculateUpVector(float xRot, float yRot) {
        return this.calculateViewVector(xRot - 90.0F, yRot);
    }

    public final Vec3 getEyePosition() {
        return new Vec3(this.getX(), this.getEyeY(), this.getZ());
    }

    public final Vec3 getEyePosition(float partialTick) {
        double d = Mth.lerp((double)partialTick, this.xo, this.getX());
        double d1 = Mth.lerp((double)partialTick, this.yo, this.getY()) + this.getEyeHeight();
        double d2 = Mth.lerp((double)partialTick, this.zo, this.getZ());
        return new Vec3(d, d1, d2);
    }

    public Vec3 getLightProbePosition(float partialTick) {
        return this.getEyePosition(partialTick);
    }

    public final Vec3 getPosition(float partialTick) {
        double d = Mth.lerp((double)partialTick, this.xo, this.getX());
        double d1 = Mth.lerp((double)partialTick, this.yo, this.getY());
        double d2 = Mth.lerp((double)partialTick, this.zo, this.getZ());
        return new Vec3(d, d1, d2);
    }

    public HitResult pick(double hitDistance, float partialTick, boolean hitFluids) {
        Vec3 eyePosition = this.getEyePosition(partialTick);
        Vec3 viewVector = this.getViewVector(partialTick);
        Vec3 vec3 = eyePosition.add(viewVector.x * hitDistance, viewVector.y * hitDistance, viewVector.z * hitDistance);
        return this.level()
            .clip(new ClipContext(eyePosition, vec3, ClipContext.Block.OUTLINE, hitFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, this));
    }

    public boolean canBeHitByProjectile() {
        return this.isAlive() && this.isPickable();
    }

    public boolean isPickable() {
        return false;
    }

    public boolean isPushable() {
        return false;
    }

    public void awardKillScore(Entity entity, DamageSource damageSource) {
        if (entity instanceof ServerPlayer) {
            CriteriaTriggers.ENTITY_KILLED_PLAYER.trigger((ServerPlayer)entity, this, damageSource);
        }
    }

    public boolean shouldRender(double x, double y, double z) {
        double d = this.getX() - x;
        double d1 = this.getY() - y;
        double d2 = this.getZ() - z;
        double d3 = d * d + d1 * d1 + d2 * d2;
        return this.shouldRenderAtSqrDistance(d3);
    }

    public boolean shouldRenderAtSqrDistance(double distance) {
        double size = this.getBoundingBox().getSize();
        if (Double.isNaN(size)) {
            size = 1.0;
        }

        size *= 64.0 * viewScale;
        return distance < size * size;
    }

    public boolean saveAsPassenger(ValueOutput output) {
        if (this.removalReason != null && !this.removalReason.shouldSave()) {
            return false;
        } else {
            String encodeId = this.getEncodeId();
            if (encodeId == null) {
                return false;
            } else {
                output.putString("id", encodeId);
                this.saveWithoutId(output);
                return true;
            }
        }
    }

    public boolean save(ValueOutput output) {
        return !this.isPassenger() && this.saveAsPassenger(output);
    }

    public void saveWithoutId(ValueOutput output) {
        try {
            if (this.vehicle != null) {
                output.store("Pos", Vec3.CODEC, new Vec3(this.vehicle.getX(), this.getY(), this.vehicle.getZ()));
            } else {
                output.store("Pos", Vec3.CODEC, this.position());
            }

            output.store("Motion", Vec3.CODEC, this.getDeltaMovement());
            output.store("Rotation", Vec2.CODEC, new Vec2(this.getYRot(), this.getXRot()));
            output.putDouble("fall_distance", this.fallDistance);
            output.putShort("Fire", (short)this.remainingFireTicks);
            output.putShort("Air", (short)this.getAirSupply());
            output.putBoolean("OnGround", this.onGround());
            output.putBoolean("Invulnerable", this.invulnerable);
            output.putInt("PortalCooldown", this.portalCooldown);
            output.store("UUID", UUIDUtil.CODEC, this.getUUID());
            output.storeNullable("CustomName", ComponentSerialization.CODEC, this.getCustomName());
            if (this.isCustomNameVisible()) {
                output.putBoolean("CustomNameVisible", this.isCustomNameVisible());
            }

            if (this.isSilent()) {
                output.putBoolean("Silent", this.isSilent());
            }

            if (this.isNoGravity()) {
                output.putBoolean("NoGravity", this.isNoGravity());
            }

            if (this.hasGlowingTag) {
                output.putBoolean("Glowing", true);
            }

            int ticksFrozen = this.getTicksFrozen();
            if (ticksFrozen > 0) {
                output.putInt("TicksFrozen", this.getTicksFrozen());
            }

            if (this.hasVisualFire) {
                output.putBoolean("HasVisualFire", this.hasVisualFire);
            }

            if (!this.tags.isEmpty()) {
                output.store("Tags", TAG_LIST_CODEC, List.copyOf(this.tags));
            }

            if (!this.customData.isEmpty()) {
                output.store("data", CustomData.CODEC, this.customData);
            }

            this.addAdditionalSaveData(output);
            if (this.isVehicle()) {
                ValueOutput.ValueOutputList valueOutputList = output.childrenList("Passengers");

                for (Entity entity : this.getPassengers()) {
                    ValueOutput valueOutput = valueOutputList.addChild();
                    if (!entity.saveAsPassenger(valueOutput)) {
                        valueOutputList.discardLast();
                    }
                }

                if (valueOutputList.isEmpty()) {
                    output.discard("Passengers");
                }
            }
        } catch (Throwable var7) {
            CrashReport crashReport = CrashReport.forThrowable(var7, "Saving entity NBT");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Entity being saved");
            this.fillCrashReportCategory(crashReportCategory);
            throw new ReportedException(crashReport);
        }
    }

    public void load(ValueInput input) {
        try {
            Vec3 vec3 = input.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
            Vec3 vec31 = input.read("Motion", Vec3.CODEC).orElse(Vec3.ZERO);
            Vec2 vec2 = input.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
            this.setDeltaMovement(Math.abs(vec31.x) > 10.0 ? 0.0 : vec31.x, Math.abs(vec31.y) > 10.0 ? 0.0 : vec31.y, Math.abs(vec31.z) > 10.0 ? 0.0 : vec31.z);
            this.needsSync = true;
            double d = 3.0000512E7;
            this.setPosRaw(Mth.clamp(vec3.x, -3.0000512E7, 3.0000512E7), Mth.clamp(vec3.y, -2.0E7, 2.0E7), Mth.clamp(vec3.z, -3.0000512E7, 3.0000512E7));
            this.setYRot(vec2.x);
            this.setXRot(vec2.y);
            this.setOldPosAndRot();
            this.setYHeadRot(this.getYRot());
            this.setYBodyRot(this.getYRot());
            this.fallDistance = input.getDoubleOr("fall_distance", 0.0);
            this.remainingFireTicks = input.getShortOr("Fire", (short)0);
            this.setAirSupply(input.getIntOr("Air", this.getMaxAirSupply()));
            this.onGround = input.getBooleanOr("OnGround", false);
            this.invulnerable = input.getBooleanOr("Invulnerable", false);
            this.portalCooldown = input.getIntOr("PortalCooldown", 0);
            input.read("UUID", UUIDUtil.CODEC).ifPresent(uuid -> {
                this.uuid = uuid;
                this.stringUUID = this.uuid.toString();
            });
            if (!Double.isFinite(this.getX()) || !Double.isFinite(this.getY()) || !Double.isFinite(this.getZ())) {
                throw new IllegalStateException("Entity has invalid position");
            } else if (Double.isFinite(this.getYRot()) && Double.isFinite(this.getXRot())) {
                this.reapplyPosition();
                this.setRot(this.getYRot(), this.getXRot());
                this.setCustomName(input.read("CustomName", ComponentSerialization.CODEC).orElse(null));
                this.setCustomNameVisible(input.getBooleanOr("CustomNameVisible", false));
                this.setSilent(input.getBooleanOr("Silent", false));
                this.setNoGravity(input.getBooleanOr("NoGravity", false));
                this.setGlowingTag(input.getBooleanOr("Glowing", false));
                this.setTicksFrozen(input.getIntOr("TicksFrozen", 0));
                this.hasVisualFire = input.getBooleanOr("HasVisualFire", false);
                this.customData = input.read("data", CustomData.CODEC).orElse(CustomData.EMPTY);
                this.tags.clear();
                input.read("Tags", TAG_LIST_CODEC).ifPresent(this.tags::addAll);
                this.readAdditionalSaveData(input);
                if (this.repositionEntityAfterLoad()) {
                    this.reapplyPosition();
                }
            } else {
                throw new IllegalStateException("Entity has invalid rotation");
            }
        } catch (Throwable var7) {
            CrashReport crashReport = CrashReport.forThrowable(var7, "Loading entity NBT");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Entity being loaded");
            this.fillCrashReportCategory(crashReportCategory);
            throw new ReportedException(crashReport);
        }
    }

    protected boolean repositionEntityAfterLoad() {
        return true;
    }

    public final @Nullable String getEncodeId() {
        EntityType<?> type = this.getType();
        Identifier key = EntityType.getKey(type);
        return !type.canSerialize() ? null : key.toString();
    }

    protected abstract void readAdditionalSaveData(ValueInput input);

    protected abstract void addAdditionalSaveData(ValueOutput output);

    public @Nullable ItemEntity spawnAtLocation(ServerLevel level, ItemLike item) {
        return this.spawnAtLocation(level, new ItemStack(item), 0.0F);
    }

    public @Nullable ItemEntity spawnAtLocation(ServerLevel level, ItemStack stack) {
        return this.spawnAtLocation(level, stack, 0.0F);
    }

    public @Nullable ItemEntity spawnAtLocation(ServerLevel level, ItemStack stack, Vec3 offset) {
        if (stack.isEmpty()) {
            return null;
        } else {
            ItemEntity itemEntity = new ItemEntity(level, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z, stack);
            itemEntity.setDefaultPickUpDelay();
            level.addFreshEntity(itemEntity);
            return itemEntity;
        }
    }

    public @Nullable ItemEntity spawnAtLocation(ServerLevel level, ItemStack stack, float yOffset) {
        return this.spawnAtLocation(level, stack, new Vec3(0.0, yOffset, 0.0));
    }

    public boolean isAlive() {
        return !this.isRemoved();
    }

    public boolean isInWall() {
        if (this.noPhysics) {
            return false;
        } else {
            float f = this.dimensions.width() * 0.8F;
            AABB aabb = AABB.ofSize(this.getEyePosition(), f, 1.0E-6, f);
            return BlockPos.betweenClosedStream(aabb)
                .anyMatch(
                    pos -> {
                        BlockState blockState = this.level().getBlockState(pos);
                        return !blockState.isAir()
                            && blockState.isSuffocating(this.level(), pos)
                            && Shapes.joinIsNotEmpty(blockState.getCollisionShape(this.level(), pos).move(pos), Shapes.create(aabb), BooleanOp.AND);
                    }
                );
        }
    }

    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level().isClientSide()
            && player.isSecondaryUseActive()
            && this instanceof Leashable leashable
            && leashable.canBeLeashed()
            && this.isAlive()
            && !(this instanceof LivingEntity livingEntity && livingEntity.isBaby())) {
            List<Leashable> list = Leashable.leashableInArea(this, leashable3 -> leashable3.getLeashHolder() == player);
            if (!list.isEmpty()) {
                boolean flag = false;

                for (Leashable leashable1 : list) {
                    if (leashable1.canHaveALeashAttachedTo(this)) {
                        leashable1.setLeashedTo(this, true);
                        flag = true;
                    }
                }

                if (flag) {
                    this.level().gameEvent(GameEvent.ENTITY_ACTION, this.blockPosition(), GameEvent.Context.of(player));
                    this.playSound(SoundEvents.LEAD_TIED);
                    return InteractionResult.SUCCESS_SERVER.withoutItem();
                }
            }
        }

        ItemStack itemInHand = player.getItemInHand(hand);
        if (itemInHand.is(Items.SHEARS) && this.shearOffAllLeashConnections(player)) {
            itemInHand.hurtAndBreak(1, player, hand);
            return InteractionResult.SUCCESS;
        } else if (this instanceof Mob mob
            && itemInHand.is(Items.SHEARS)
            && mob.canShearEquipment(player)
            && !player.isSecondaryUseActive()
            && this.attemptToShearEquipment(player, hand, itemInHand, mob)) {
            return InteractionResult.SUCCESS;
        } else {
            if (this.isAlive() && this instanceof Leashable leashable2) {
                if (leashable2.getLeashHolder() == player) {
                    if (!this.level().isClientSide()) {
                        if (player.hasInfiniteMaterials()) {
                            leashable2.removeLeash();
                        } else {
                            leashable2.dropLeash();
                        }

                        this.gameEvent(GameEvent.ENTITY_INTERACT, player);
                        this.playSound(SoundEvents.LEAD_UNTIED);
                    }

                    return InteractionResult.SUCCESS.withoutItem();
                }

                ItemStack itemInHand1 = player.getItemInHand(hand);
                if (itemInHand1.is(Items.LEAD) && !(leashable2.getLeashHolder() instanceof Player)) {
                    if (this.level().isClientSide()) {
                        return InteractionResult.CONSUME;
                    }

                    if (leashable2.canHaveALeashAttachedTo(player)) {
                        if (leashable2.isLeashed()) {
                            leashable2.dropLeash();
                        }

                        leashable2.setLeashedTo(player, true);
                        this.playSound(SoundEvents.LEAD_TIED);
                        itemInHand1.shrink(1);
                        return InteractionResult.SUCCESS_SERVER;
                    }
                }
            }

            return InteractionResult.PASS;
        }
    }

    public boolean shearOffAllLeashConnections(@Nullable Player player) {
        boolean flag = this.dropAllLeashConnections(player);
        if (flag && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, this.blockPosition(), SoundEvents.SHEARS_SNIP, player != null ? player.getSoundSource() : this.getSoundSource());
        }

        return flag;
    }

    public boolean dropAllLeashConnections(@Nullable Player player) {
        List<Leashable> list = Leashable.leashableLeashedTo(this);
        boolean flag = !list.isEmpty();
        if (this instanceof Leashable leashable && leashable.isLeashed()) {
            leashable.dropLeash();
            flag = true;
        }

        for (Leashable leashable1 : list) {
            leashable1.dropLeash();
        }

        if (flag) {
            this.gameEvent(GameEvent.SHEAR, player);
            return true;
        } else {
            return false;
        }
    }

    private boolean attemptToShearEquipment(Player player, InteractionHand hand, ItemStack stack, Mob mob) {
        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            ItemStack itemBySlot = mob.getItemBySlot(equipmentSlot);
            Equippable equippable = itemBySlot.get(DataComponents.EQUIPPABLE);
            if (equippable != null
                && equippable.canBeSheared()
                && (!EnchantmentHelper.has(itemBySlot, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE) || player.isCreative())) {
                stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
                Vec3 average = this.dimensions.attachments().getAverage(EntityAttachment.PASSENGER);
                mob.setItemSlotAndDropWhenKilled(equipmentSlot, ItemStack.EMPTY);
                this.gameEvent(GameEvent.SHEAR, player);
                this.playSound(equippable.shearingSound().value());
                if (this.level() instanceof ServerLevel serverLevel) {
                    this.spawnAtLocation(serverLevel, itemBySlot, average);
                    CriteriaTriggers.PLAYER_SHEARED_EQUIPMENT.trigger((ServerPlayer)player, itemBySlot, mob);
                }

                return true;
            }
        }

        return false;
    }

    public boolean canCollideWith(Entity entity) {
        return entity.canBeCollidedWith(this) && !this.isPassengerOfSameVehicle(entity);
    }

    public boolean canBeCollidedWith(@Nullable Entity entity) {
        return false;
    }

    public void rideTick() {
        this.setDeltaMovement(Vec3.ZERO);
        this.tick();
        if (this.isPassenger()) {
            this.getVehicle().positionRider(this);
        }
    }

    public final void positionRider(Entity passenger) {
        if (this.hasPassenger(passenger)) {
            this.positionRider(passenger, Entity::setPos);
        }
    }

    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        Vec3 passengerRidingPosition = this.getPassengerRidingPosition(passenger);
        Vec3 vehicleAttachmentPoint = passenger.getVehicleAttachmentPoint(this);
        callback.accept(
            passenger,
            passengerRidingPosition.x - vehicleAttachmentPoint.x,
            passengerRidingPosition.y - vehicleAttachmentPoint.y,
            passengerRidingPosition.z - vehicleAttachmentPoint.z
        );
    }

    public void onPassengerTurned(Entity entityToUpdate) {
    }

    public Vec3 getVehicleAttachmentPoint(Entity entity) {
        return this.getAttachments().get(EntityAttachment.VEHICLE, 0, this.yRot);
    }

    public Vec3 getPassengerRidingPosition(Entity entity) {
        return this.position().add(this.getPassengerAttachmentPoint(entity, this.dimensions, 1.0F));
    }

    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        return getDefaultPassengerAttachmentPoint(this, entity, dimensions.attachments());
    }

    protected static Vec3 getDefaultPassengerAttachmentPoint(Entity vehicle, Entity passenger, EntityAttachments attachments) {
        int index = vehicle.getPassengers().indexOf(passenger);
        return attachments.getClamped(EntityAttachment.PASSENGER, index, vehicle.yRot);
    }

    public final boolean startRiding(Entity entity) {
        return this.startRiding(entity, false, true);
    }

    public boolean showVehicleHealth() {
        return this instanceof LivingEntity;
    }

    public boolean startRiding(Entity entity, boolean force, boolean triggerEvents) {
        if (entity == this.vehicle) {
            return false;
        } else if (!entity.couldAcceptPassenger()) {
            return false;
        } else if (!this.level().isClientSide() && !entity.type.canSerialize()) {
            return false;
        } else {
            for (Entity entity1 = entity; entity1.vehicle != null; entity1 = entity1.vehicle) {
                if (entity1.vehicle == this) {
                    return false;
                }
            }

            if (force || this.canRide(entity) && entity.canAddPassenger(this)) {
                if (this.isPassenger()) {
                    this.stopRiding();
                }

                this.setPose(Pose.STANDING);
                this.vehicle = entity;
                this.vehicle.addPassenger(this);
                if (triggerEvents) {
                    this.level().gameEvent(this, GameEvent.ENTITY_MOUNT, this.vehicle.position);
                    entity.getIndirectPassengersStream()
                        .filter(entity2 -> entity2 instanceof ServerPlayer)
                        .forEach(entity2 -> CriteriaTriggers.START_RIDING_TRIGGER.trigger((ServerPlayer)entity2));
                }

                return true;
            } else {
                return false;
            }
        }
    }

    protected boolean canRide(Entity vehicle) {
        return !this.isShiftKeyDown() && this.boardingCooldown <= 0;
    }

    public void ejectPassengers() {
        for (int i = this.passengers.size() - 1; i >= 0; i--) {
            this.passengers.get(i).stopRiding();
        }
    }

    public void removeVehicle() {
        if (this.vehicle != null) {
            Entity entity = this.vehicle;
            this.vehicle = null;
            entity.removePassenger(this);
            Entity.RemovalReason removalReason = this.getRemovalReason();
            if (removalReason == null || removalReason.shouldDestroy()) {
                this.level().gameEvent(this, GameEvent.ENTITY_DISMOUNT, entity.position);
            }
        }
    }

    public void stopRiding() {
        this.removeVehicle();
    }

    protected void addPassenger(Entity passenger) {
        if (passenger.getVehicle() != this) {
            throw new IllegalStateException("Use x.startRiding(y), not y.addPassenger(x)");
        } else {
            if (this.passengers.isEmpty()) {
                this.passengers = ImmutableList.of(passenger);
            } else {
                List<Entity> list = Lists.newArrayList(this.passengers);
                if (!this.level().isClientSide() && passenger instanceof Player && !(this.getFirstPassenger() instanceof Player)) {
                    list.add(0, passenger);
                } else {
                    list.add(passenger);
                }

                this.passengers = ImmutableList.copyOf(list);
            }
        }
    }

    protected void removePassenger(Entity passenger) {
        if (passenger.getVehicle() == this) {
            throw new IllegalStateException("Use x.stopRiding(y), not y.removePassenger(x)");
        } else {
            if (this.passengers.size() == 1 && this.passengers.get(0) == passenger) {
                this.passengers = ImmutableList.of();
            } else {
                this.passengers = this.passengers.stream().filter(entity -> entity != passenger).collect(ImmutableList.toImmutableList());
            }

            passenger.boardingCooldown = 60;
        }
    }

    protected boolean canAddPassenger(Entity passenger) {
        return this.passengers.isEmpty();
    }

    protected boolean couldAcceptPassenger() {
        return true;
    }

    public final boolean isInterpolating() {
        return this.getInterpolation() != null && this.getInterpolation().hasActiveInterpolation();
    }

    public final void moveOrInterpolateTo(Vec3 pos, float yRot, float xRot) {
        this.moveOrInterpolateTo(Optional.of(pos), Optional.of(yRot), Optional.of(xRot));
    }

    public final void moveOrInterpolateTo(float yRot, float xRot) {
        this.moveOrInterpolateTo(Optional.empty(), Optional.of(yRot), Optional.of(xRot));
    }

    public final void moveOrInterpolateTo(Vec3 pos) {
        this.moveOrInterpolateTo(Optional.of(pos), Optional.empty(), Optional.empty());
    }

    public final void moveOrInterpolateTo(Optional<Vec3> pos, Optional<Float> yRot, Optional<Float> xRot) {
        InterpolationHandler interpolation = this.getInterpolation();
        if (interpolation != null) {
            interpolation.interpolateTo(pos.orElse(interpolation.position()), yRot.orElse(interpolation.yRot()), xRot.orElse(interpolation.xRot()));
        } else {
            pos.ifPresent(this::setPos);
            yRot.ifPresent(_float -> this.setYRot(_float % 360.0F));
            xRot.ifPresent(_float -> this.setXRot(_float % 360.0F));
        }
    }

    public @Nullable InterpolationHandler getInterpolation() {
        return null;
    }

    public void lerpHeadTo(float yRot, int steps) {
        this.setYHeadRot(yRot);
    }

    public float getPickRadius() {
        return 0.0F;
    }

    public Vec3 getLookAngle() {
        return this.calculateViewVector(this.getXRot(), this.getYRot());
    }

    public Vec3 getHeadLookAngle() {
        return this.calculateViewVector(this.getXRot(), this.getYHeadRot());
    }

    public Vec3 getHandHoldingItemAngle(Item item) {
        if (!(this instanceof Player player)) {
            return Vec3.ZERO;
        } else {
            boolean flag = player.getOffhandItem().is(item) && !player.getMainHandItem().is(item);
            HumanoidArm humanoidArm = flag ? player.getMainArm().getOpposite() : player.getMainArm();
            return this.calculateViewVector(0.0F, this.getYRot() + (humanoidArm == HumanoidArm.RIGHT ? 80 : -80)).scale(0.5);
        }
    }

    public Vec2 getRotationVector() {
        return new Vec2(this.getXRot(), this.getYRot());
    }

    public Vec3 getForward() {
        return Vec3.directionFromRotation(this.getRotationVector());
    }

    public void setAsInsidePortal(Portal portal, BlockPos pos) {
        if (this.isOnPortalCooldown()) {
            this.setPortalCooldown();
        } else {
            if (this.portalProcess == null || !this.portalProcess.isSamePortal(portal)) {
                this.portalProcess = new PortalProcessor(portal, pos.immutable());
            } else if (!this.portalProcess.isInsidePortalThisTick()) {
                this.portalProcess.updateEntryPosition(pos.immutable());
                this.portalProcess.setAsInsidePortalThisTick(true);
            }
        }
    }

    protected void handlePortal() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.processPortalCooldown();
            if (this.portalProcess != null) {
                if (this.portalProcess.processPortalTeleportation(serverLevel, this, this.canUsePortal(false))) {
                    ProfilerFiller profilerFiller = Profiler.get();
                    profilerFiller.push("portal");
                    this.setPortalCooldown();
                    TeleportTransition portalDestination = this.portalProcess.getPortalDestination(serverLevel, this);
                    if (portalDestination != null) {
                        ServerLevel level = portalDestination.newLevel();
                        if (serverLevel.isAllowedToEnterPortal(level) && (level.dimension() == serverLevel.dimension() || this.canTeleport(serverLevel, level))
                            )
                         {
                            this.teleport(portalDestination);
                        }
                    }

                    profilerFiller.pop();
                } else if (this.portalProcess.hasExpired()) {
                    this.portalProcess = null;
                }
            }
        }
    }

    public int getDimensionChangingDelay() {
        Entity firstPassenger = this.getFirstPassenger();
        return firstPassenger instanceof ServerPlayer ? firstPassenger.getDimensionChangingDelay() : 300;
    }

    public void lerpMotion(Vec3 movement) {
        this.setDeltaMovement(movement);
    }

    public void handleDamageEvent(DamageSource damageSource) {
    }

    public void handleEntityEvent(byte id) {
        switch (id) {
            case 53:
                HoneyBlock.showSlideParticles(this);
        }
    }

    public void animateHurt(float yaw) {
    }

    public boolean isOnFire() {
        boolean flag = this.level() != null && this.level().isClientSide();
        return !this.fireImmune() && (this.remainingFireTicks > 0 || flag && this.getSharedFlag(FLAG_ONFIRE));
    }

    public boolean isPassenger() {
        return this.getVehicle() != null;
    }

    public boolean isVehicle() {
        return !this.passengers.isEmpty();
    }

    public boolean dismountsUnderwater() {
        return this.getType().is(EntityTypeTags.DISMOUNTS_UNDERWATER);
    }

    public boolean canControlVehicle() {
        return !this.getType().is(EntityTypeTags.NON_CONTROLLING_RIDER);
    }

    public void setShiftKeyDown(boolean keyDown) {
        this.setSharedFlag(FLAG_SHIFT_KEY_DOWN, keyDown);
    }

    public boolean isShiftKeyDown() {
        return this.getSharedFlag(FLAG_SHIFT_KEY_DOWN);
    }

    public boolean isSteppingCarefully() {
        return this.isShiftKeyDown();
    }

    public boolean isSuppressingBounce() {
        return this.isShiftKeyDown();
    }

    public boolean isDiscrete() {
        return this.isShiftKeyDown();
    }

    public boolean isDescending() {
        return this.isShiftKeyDown();
    }

    public boolean isCrouching() {
        return this.hasPose(Pose.CROUCHING);
    }

    public boolean isSprinting() {
        return this.getSharedFlag(FLAG_SPRINTING);
    }

    public void setSprinting(boolean sprinting) {
        this.setSharedFlag(FLAG_SPRINTING, sprinting);
    }

    public boolean isSwimming() {
        return this.getSharedFlag(FLAG_SWIMMING);
    }

    public boolean isVisuallySwimming() {
        return this.hasPose(Pose.SWIMMING);
    }

    public boolean isVisuallyCrawling() {
        return this.isVisuallySwimming() && !this.isInWater();
    }

    public void setSwimming(boolean swimming) {
        this.setSharedFlag(FLAG_SWIMMING, swimming);
    }

    public final boolean hasGlowingTag() {
        return this.hasGlowingTag;
    }

    public final void setGlowingTag(boolean hasGlowingTag) {
        this.hasGlowingTag = hasGlowingTag;
        this.setSharedFlag(FLAG_GLOWING, this.isCurrentlyGlowing());
    }

    public boolean isCurrentlyGlowing() {
        return this.level().isClientSide() ? this.getSharedFlag(FLAG_GLOWING) : this.hasGlowingTag;
    }

    public boolean isInvisible() {
        return this.getSharedFlag(FLAG_INVISIBLE);
    }

    public boolean isInvisibleTo(Player player) {
        if (player.isSpectator()) {
            return false;
        } else {
            Team team = this.getTeam();
            return (team == null || player == null || player.getTeam() != team || !team.canSeeFriendlyInvisibles()) && this.isInvisible();
        }
    }

    public boolean isOnRails() {
        return false;
    }

    public void updateDynamicGameEventListener(BiConsumer<DynamicGameEventListener<?>, ServerLevel> listenerConsumer) {
    }

    public @Nullable PlayerTeam getTeam() {
        return this.level().getScoreboard().getPlayersTeam(this.getScoreboardName());
    }

    public final boolean isAlliedTo(@Nullable Entity entity) {
        return entity != null && (this == entity || this.considersEntityAsAlly(entity) || entity.considersEntityAsAlly(this));
    }

    protected boolean considersEntityAsAlly(Entity entity) {
        return this.isAlliedTo(entity.getTeam());
    }

    public boolean isAlliedTo(@Nullable Team team) {
        return this.getTeam() != null && this.getTeam().isAlliedTo(team);
    }

    public void setInvisible(boolean invisible) {
        this.setSharedFlag(FLAG_INVISIBLE, invisible);
    }

    public boolean getSharedFlag(int flag) {
        return (this.entityData.get(DATA_SHARED_FLAGS_ID) & 1 << flag) != 0;
    }

    public void setSharedFlag(int flag, boolean set) {
        byte b = this.entityData.get(DATA_SHARED_FLAGS_ID);
        if (set) {
            this.entityData.set(DATA_SHARED_FLAGS_ID, (byte)(b | 1 << flag));
        } else {
            this.entityData.set(DATA_SHARED_FLAGS_ID, (byte)(b & ~(1 << flag)));
        }
    }

    public int getMaxAirSupply() {
        return 300;
    }

    public int getAirSupply() {
        return this.entityData.get(DATA_AIR_SUPPLY_ID);
    }

    public void setAirSupply(int airSupply) {
        this.entityData.set(DATA_AIR_SUPPLY_ID, airSupply);
    }

    public void clearFreeze() {
        this.setTicksFrozen(0);
    }

    public int getTicksFrozen() {
        return this.entityData.get(DATA_TICKS_FROZEN);
    }

    public void setTicksFrozen(int ticksFrozen) {
        this.entityData.set(DATA_TICKS_FROZEN, ticksFrozen);
    }

    public float getPercentFrozen() {
        int ticksRequiredToFreeze = this.getTicksRequiredToFreeze();
        return (float)Math.min(this.getTicksFrozen(), ticksRequiredToFreeze) / ticksRequiredToFreeze;
    }

    public boolean isFullyFrozen() {
        return this.getTicksFrozen() >= this.getTicksRequiredToFreeze();
    }

    public int getTicksRequiredToFreeze() {
        return 140;
    }

    public void thunderHit(ServerLevel level, LightningBolt lightning) {
        this.setRemainingFireTicks(this.remainingFireTicks + 1);
        if (this.remainingFireTicks == 0) {
            this.igniteForSeconds(8.0F);
        }

        this.hurtServer(level, this.damageSources().lightningBolt(), 5.0F);
    }

    public void onAboveBubbleColumn(boolean downwards, BlockPos pos) {
        handleOnAboveBubbleColumn(this, downwards, pos);
    }

    protected static void handleOnAboveBubbleColumn(Entity entity, boolean downwards, BlockPos pos) {
        Vec3 deltaMovement = entity.getDeltaMovement();
        double max;
        if (downwards) {
            max = Math.max(-0.9, deltaMovement.y - 0.03);
        } else {
            max = Math.min(1.8, deltaMovement.y + 0.1);
        }

        entity.setDeltaMovement(deltaMovement.x, max, deltaMovement.z);
        sendBubbleColumnParticles(entity.level, pos);
    }

    protected static void sendBubbleColumnParticles(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 2; i++) {
                serverLevel.sendParticles(
                    ParticleTypes.SPLASH, pos.getX() + level.random.nextDouble(), pos.getY() + 1, pos.getZ() + level.random.nextDouble(), 1, 0.0, 0.0, 0.0, 1.0
                );
                serverLevel.sendParticles(
                    ParticleTypes.BUBBLE,
                    pos.getX() + level.random.nextDouble(),
                    pos.getY() + 1,
                    pos.getZ() + level.random.nextDouble(),
                    1,
                    0.0,
                    0.01,
                    0.0,
                    0.2
                );
            }
        }
    }

    public void onInsideBubbleColumn(boolean downwards) {
        handleOnInsideBubbleColumn(this, downwards);
    }

    protected static void handleOnInsideBubbleColumn(Entity entity, boolean downwards) {
        Vec3 deltaMovement = entity.getDeltaMovement();
        double max;
        if (downwards) {
            max = Math.max(-0.3, deltaMovement.y - 0.03);
        } else {
            max = Math.min(0.7, deltaMovement.y + 0.06);
        }

        entity.setDeltaMovement(deltaMovement.x, max, deltaMovement.z);
        entity.resetFallDistance();
    }

    public boolean killedEntity(ServerLevel level, LivingEntity entity, DamageSource damageSource) {
        return true;
    }

    public void checkFallDistanceAccumulation() {
        if (this.getDeltaMovement().y() > -0.5 && this.fallDistance > 1.0) {
            this.fallDistance = 1.0;
        }
    }

    public void resetFallDistance() {
        this.fallDistance = 0.0;
    }

    protected void moveTowardsClosestSpace(double x, double y, double z) {
        BlockPos blockPos = BlockPos.containing(x, y, z);
        Vec3 vec3 = new Vec3(x - blockPos.getX(), y - blockPos.getY(), z - blockPos.getZ());
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        Direction direction = Direction.UP;
        double d = Double.MAX_VALUE;

        for (Direction direction1 : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            mutableBlockPos.setWithOffset(blockPos, direction1);
            if (!this.level().getBlockState(mutableBlockPos).isCollisionShapeFullBlock(this.level(), mutableBlockPos)) {
                double d1 = vec3.get(direction1.getAxis());
                double d2 = direction1.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - d1 : d1;
                if (d2 < d) {
                    d = d2;
                    direction = direction1;
                }
            }
        }

        float f = this.random.nextFloat() * 0.2F + 0.1F;
        float f1 = direction.getAxisDirection().getStep();
        Vec3 vec31 = this.getDeltaMovement().scale(0.75);
        if (direction.getAxis() == Direction.Axis.X) {
            this.setDeltaMovement(f1 * f, vec31.y, vec31.z);
        } else if (direction.getAxis() == Direction.Axis.Y) {
            this.setDeltaMovement(vec31.x, f1 * f, vec31.z);
        } else if (direction.getAxis() == Direction.Axis.Z) {
            this.setDeltaMovement(vec31.x, vec31.y, f1 * f);
        }
    }

    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
        this.resetFallDistance();
        this.stuckSpeedMultiplier = motionMultiplier;
    }

    private static Component removeAction(Component name) {
        MutableComponent mutableComponent = name.plainCopy().setStyle(name.getStyle().withClickEvent(null));

        for (Component component : name.getSiblings()) {
            mutableComponent.append(removeAction(component));
        }

        return mutableComponent;
    }

    @Override
    public Component getName() {
        Component customName = this.getCustomName();
        return customName != null ? removeAction(customName) : this.getTypeName();
    }

    protected Component getTypeName() {
        return this.type.getDescription();
    }

    public boolean is(Entity entity) {
        return this == entity;
    }

    public float getYHeadRot() {
        return 0.0F;
    }

    public void setYHeadRot(float yHeadRot) {
    }

    public void setYBodyRot(float yBodyRot) {
    }

    public boolean isAttackable() {
        return true;
    }

    public boolean skipAttackInteraction(Entity entity) {
        return false;
    }

    @Override
    public String toString() {
        String string = this.level() == null ? "~NULL~" : this.level().toString();
        return this.removalReason != null
            ? String.format(
                Locale.ROOT,
                "%s['%s'/%d, l='%s', x=%.2f, y=%.2f, z=%.2f, removed=%s]",
                this.getClass().getSimpleName(),
                this.getPlainTextName(),
                this.id,
                string,
                this.getX(),
                this.getY(),
                this.getZ(),
                this.removalReason
            )
            : String.format(
                Locale.ROOT,
                "%s['%s'/%d, l='%s', x=%.2f, y=%.2f, z=%.2f]",
                this.getClass().getSimpleName(),
                this.getPlainTextName(),
                this.id,
                string,
                this.getX(),
                this.getY(),
                this.getZ()
            );
    }

    public final boolean isInvulnerableToBase(DamageSource damageSource) {
        return this.isRemoved()
            || this.invulnerable && !damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !damageSource.isCreativePlayer()
            || damageSource.is(DamageTypeTags.IS_FIRE) && this.fireImmune()
            || damageSource.is(DamageTypeTags.IS_FALL) && this.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE);
    }

    public boolean isInvulnerable() {
        return this.invulnerable;
    }

    public void setInvulnerable(boolean invulnerable) {
        this.invulnerable = invulnerable;
    }

    public void copyPosition(Entity entity) {
        this.snapTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
    }

    public void restoreFrom(Entity entity) {
        try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(this.problemPath(), LOGGER)) {
            TagValueOutput tagValueOutput = TagValueOutput.createWithContext(scopedCollector, entity.registryAccess());
            entity.saveWithoutId(tagValueOutput);
            this.load(TagValueInput.create(scopedCollector, this.registryAccess(), tagValueOutput.buildResult()));
        }

        this.portalCooldown = entity.portalCooldown;
        this.portalProcess = entity.portalProcess;
    }

    public @Nullable Entity teleport(TeleportTransition teleportTransition) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved()) {
            ServerLevel level = teleportTransition.newLevel();
            boolean flag = level.dimension() != serverLevel.dimension();
            if (!teleportTransition.asPassenger()) {
                this.stopRiding();
            }

            return flag ? this.teleportCrossDimension(serverLevel, level, teleportTransition) : this.teleportSameDimension(serverLevel, teleportTransition);
        } else {
            return null;
        }
    }

    private Entity teleportSameDimension(ServerLevel level, TeleportTransition teleportTransition) {
        for (Entity entity : this.getPassengers()) {
            entity.teleport(this.calculatePassengerTransition(teleportTransition, entity));
        }

        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("teleportSameDimension");
        this.teleportSetPosition(PositionMoveRotation.of(teleportTransition), teleportTransition.relatives());
        if (!teleportTransition.asPassenger()) {
            this.sendTeleportTransitionToRidingPlayers(teleportTransition);
        }

        teleportTransition.postTeleportTransition().onTransition(this);
        profilerFiller.pop();
        return this;
    }

    private @Nullable Entity teleportCrossDimension(ServerLevel oldLevel, ServerLevel newLevel, TeleportTransition teleportTransition) {
        List<Entity> passengers = this.getPassengers();
        List<Entity> list = new ArrayList<>(passengers.size());
        this.ejectPassengers();

        for (Entity entity : passengers) {
            Entity entity1 = entity.teleport(this.calculatePassengerTransition(teleportTransition, entity));
            if (entity1 != null) {
                list.add(entity1);
            }
        }

        ProfilerFiller profilerFiller = Profiler.get();
        profilerFiller.push("teleportCrossDimension");
        Entity entityx = this.getType().create(newLevel, EntitySpawnReason.DIMENSION_TRAVEL);
        if (entityx == null) {
            profilerFiller.pop();
            return null;
        } else {
            entityx.restoreFrom(this);
            this.removeAfterChangingDimensions();
            entityx.teleportSetPosition(PositionMoveRotation.of(this), PositionMoveRotation.of(teleportTransition), teleportTransition.relatives());
            newLevel.addDuringTeleport(entityx);

            for (Entity entity2 : list) {
                entity2.startRiding(entityx, true, false);
            }

            newLevel.resetEmptyTime();
            teleportTransition.postTeleportTransition().onTransition(entityx);
            this.teleportSpectators(teleportTransition, oldLevel);
            profilerFiller.pop();
            return entityx;
        }
    }

    protected void teleportSpectators(TeleportTransition teleportTransition, ServerLevel oldLevel) {
        for (ServerPlayer serverPlayer : List.copyOf(oldLevel.players())) {
            if (serverPlayer.getCamera() == this) {
                serverPlayer.teleport(teleportTransition);
                serverPlayer.setCamera(null);
            }
        }
    }

    private TeleportTransition calculatePassengerTransition(TeleportTransition teleportTransition, Entity entity) {
        float f = teleportTransition.yRot() + (teleportTransition.relatives().contains(Relative.Y_ROT) ? 0.0F : entity.getYRot() - this.getYRot());
        float f1 = teleportTransition.xRot() + (teleportTransition.relatives().contains(Relative.X_ROT) ? 0.0F : entity.getXRot() - this.getXRot());
        Vec3 vec3 = entity.position().subtract(this.position());
        Vec3 vec31 = teleportTransition.position()
            .add(
                teleportTransition.relatives().contains(Relative.X) ? 0.0 : vec3.x(),
                teleportTransition.relatives().contains(Relative.Y) ? 0.0 : vec3.y(),
                teleportTransition.relatives().contains(Relative.Z) ? 0.0 : vec3.z()
            );
        return teleportTransition.withPosition(vec31).withRotation(f, f1).transitionAsPassenger();
    }

    private void sendTeleportTransitionToRidingPlayers(TeleportTransition teleportTransition) {
        Entity controllingPassenger = this.getControllingPassenger();

        for (Entity entity : this.getIndirectPassengers()) {
            if (entity instanceof ServerPlayer serverPlayer) {
                if (controllingPassenger != null && serverPlayer.getId() == controllingPassenger.getId()) {
                    serverPlayer.connection
                        .send(
                            ClientboundTeleportEntityPacket.teleport(
                                this.getId(), PositionMoveRotation.of(teleportTransition), teleportTransition.relatives(), this.onGround
                            )
                        );
                } else {
                    serverPlayer.connection
                        .send(ClientboundTeleportEntityPacket.teleport(this.getId(), PositionMoveRotation.of(this), Set.of(), this.onGround));
                }
            }
        }
    }

    public void teleportSetPosition(PositionMoveRotation positionMovementRotation, Set<Relative> relatives) {
        this.teleportSetPosition(PositionMoveRotation.of(this), positionMovementRotation, relatives);
    }

    public void teleportSetPosition(PositionMoveRotation currentPos, PositionMoveRotation afterPos, Set<Relative> relatives) {
        PositionMoveRotation positionMoveRotation = PositionMoveRotation.calculateAbsolute(currentPos, afterPos, relatives);
        this.setPosRaw(positionMoveRotation.position().x, positionMoveRotation.position().y, positionMoveRotation.position().z);
        this.setYRot(positionMoveRotation.yRot());
        this.setYHeadRot(positionMoveRotation.yRot());
        this.setXRot(positionMoveRotation.xRot());
        this.reapplyPosition();
        this.setOldPosAndRot();
        this.setDeltaMovement(positionMoveRotation.deltaMovement());
        this.clearMovementThisTick();
    }

    public void forceSetRotation(float yRot, boolean yRelative, float xRot, boolean xRelative) {
        Set<Relative> set = Relative.rotation(yRelative, xRelative);
        PositionMoveRotation positionMoveRotation = PositionMoveRotation.of(this);
        PositionMoveRotation positionMoveRotation1 = positionMoveRotation.withRotation(yRot, xRot);
        PositionMoveRotation positionMoveRotation2 = PositionMoveRotation.calculateAbsolute(positionMoveRotation, positionMoveRotation1, set);
        this.setYRot(positionMoveRotation2.yRot());
        this.setYHeadRot(positionMoveRotation2.yRot());
        this.setXRot(positionMoveRotation2.xRot());
        this.setOldRot();
    }

    public void placePortalTicket(BlockPos pos) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(pos), 3);
        }
    }

    protected void removeAfterChangingDimensions() {
        this.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        if (this instanceof Leashable leashable) {
            leashable.removeLeash();
        }

        if (this instanceof WaypointTransmitter waypointTransmitter && this.level instanceof ServerLevel serverLevel) {
            serverLevel.getWaypointManager().untrackWaypoint(waypointTransmitter);
        }
    }

    public Vec3 getRelativePortalPosition(Direction.Axis axis, BlockUtil.FoundRectangle portal) {
        return PortalShape.getRelativePosition(portal, axis, this.position(), this.getDimensions(this.getPose()));
    }

    public boolean canUsePortal(boolean allowPassengers) {
        return (allowPassengers || !this.isPassenger()) && this.isAlive();
    }

    public boolean canTeleport(Level fromLevel, Level toLevel) {
        if (fromLevel.dimension() == Level.END && toLevel.dimension() == Level.OVERWORLD) {
            for (Entity entity : this.getPassengers()) {
                if (entity instanceof ServerPlayer serverPlayer && !serverPlayer.seenCredits) {
                    return false;
                }
            }
        }

        return true;
    }

    public float getBlockExplosionResistance(
        Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, FluidState fluidState, float explosionPower
    ) {
        return explosionPower;
    }

    public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float explosionPower) {
        return true;
    }

    public int getMaxFallDistance() {
        return 3;
    }

    public boolean isIgnoringBlockTriggers() {
        return false;
    }

    public void fillCrashReportCategory(CrashReportCategory category) {
        category.setDetail("Entity Type", () -> EntityType.getKey(this.getType()) + " (" + this.getClass().getCanonicalName() + ")");
        category.setDetail("Entity ID", this.id);
        category.setDetail("Entity Name", () -> this.getPlainTextName());
        category.setDetail("Entity's Exact location", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", this.getX(), this.getY(), this.getZ()));
        category.setDetail(
            "Entity's Block location", CrashReportCategory.formatLocation(this.level(), Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()))
        );
        Vec3 deltaMovement = this.getDeltaMovement();
        category.setDetail("Entity's Momentum", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", deltaMovement.x, deltaMovement.y, deltaMovement.z));
        category.setDetail("Entity's Passengers", () -> this.getPassengers().toString());
        category.setDetail("Entity's Vehicle", () -> String.valueOf(this.getVehicle()));
    }

    public boolean displayFireAnimation() {
        return this.isOnFire() && !this.isSpectator();
    }

    public void setUUID(UUID uuid) {
        this.uuid = uuid;
        this.stringUUID = this.uuid.toString();
    }

    @Override
    public UUID getUUID() {
        return this.uuid;
    }

    public String getStringUUID() {
        return this.stringUUID;
    }

    @Override
    public String getScoreboardName() {
        return this.stringUUID;
    }

    public boolean isPushedByFluid() {
        return true;
    }

    public static double getViewScale() {
        return viewScale;
    }

    public static void setViewScale(double renderDistWeight) {
        viewScale = renderDistWeight;
    }

    @Override
    public Component getDisplayName() {
        return PlayerTeam.formatNameForTeam(this.getTeam(), this.getName())
            .withStyle(style -> style.withHoverEvent(this.createHoverEvent()).withInsertion(this.getStringUUID()));
    }

    public void setCustomName(@Nullable Component name) {
        this.entityData.set(DATA_CUSTOM_NAME, Optional.ofNullable(name));
    }

    @Override
    public @Nullable Component getCustomName() {
        return this.entityData.get(DATA_CUSTOM_NAME).orElse(null);
    }

    @Override
    public boolean hasCustomName() {
        return this.entityData.get(DATA_CUSTOM_NAME).isPresent();
    }

    public void setCustomNameVisible(boolean visible) {
        this.entityData.set(DATA_CUSTOM_NAME_VISIBLE, visible);
    }

    public boolean isCustomNameVisible() {
        return this.entityData.get(DATA_CUSTOM_NAME_VISIBLE);
    }

    public boolean teleportTo(ServerLevel level, double x, double y, double z, Set<Relative> relativeMovements, float yaw, float pitch, boolean setCamera) {
        Entity entity = this.teleport(new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, yaw, pitch, relativeMovements, TeleportTransition.DO_NOTHING));
        return entity != null;
    }

    public void dismountTo(double x, double y, double z) {
        this.teleportTo(x, y, z);
    }

    public void teleportTo(double x, double y, double z) {
        if (this.level() instanceof ServerLevel) {
            this.snapTo(x, y, z, this.getYRot(), this.getXRot());
            this.teleportPassengers();
        }
    }

    public void teleportPassengers() {
        this.getSelfAndPassengers().forEach(entity -> {
            for (Entity entity1 : entity.passengers) {
                entity.positionRider(entity1, Entity::snapTo);
            }
        });
    }

    public void teleportRelative(double dx, double dy, double dz) {
        this.teleportTo(this.getX() + dx, this.getY() + dy, this.getZ() + dz);
    }

    public boolean shouldShowName() {
        return this.isCustomNameVisible();
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> dataValues) {
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_POSE.equals(key)) {
            this.refreshDimensions();
        }
    }

    @Deprecated
    protected void fixupDimensions() {
        Pose pose = this.getPose();
        EntityDimensions dimensions = this.getDimensions(pose);
        this.dimensions = dimensions;
        this.eyeHeight = dimensions.eyeHeight();
    }

    public void refreshDimensions() {
        EntityDimensions entityDimensions = this.dimensions;
        Pose pose = this.getPose();
        EntityDimensions dimensions = this.getDimensions(pose);
        this.dimensions = dimensions;
        this.eyeHeight = dimensions.eyeHeight();
        this.reapplyPosition();
        boolean flag = dimensions.width() <= 4.0F && dimensions.height() <= 4.0F;
        if (!this.level.isClientSide()
            && !this.firstTick
            && !this.noPhysics
            && flag
            && (dimensions.width() > entityDimensions.width() || dimensions.height() > entityDimensions.height())
            && !(this instanceof Player)) {
            this.fudgePositionAfterSizeChange(entityDimensions);
        }
    }

    public boolean fudgePositionAfterSizeChange(EntityDimensions dimensions) {
        EntityDimensions dimensions1 = this.getDimensions(this.getPose());
        Vec3 vec3 = this.position().add(0.0, dimensions.height() / 2.0, 0.0);
        double d = Math.max(0.0F, dimensions1.width() - dimensions.width()) + 1.0E-6;
        double d1 = Math.max(0.0F, dimensions1.height() - dimensions.height()) + 1.0E-6;
        VoxelShape voxelShape = Shapes.create(AABB.ofSize(vec3, d, d1, d));
        Optional<Vec3> optional = this.level.findFreePosition(this, voxelShape, vec3, dimensions1.width(), dimensions1.height(), dimensions1.width());
        if (optional.isPresent()) {
            this.setPos(optional.get().add(0.0, -dimensions1.height() / 2.0, 0.0));
            return true;
        } else {
            if (dimensions1.width() > dimensions.width() && dimensions1.height() > dimensions.height()) {
                VoxelShape voxelShape1 = Shapes.create(AABB.ofSize(vec3, d, 1.0E-6, d));
                Optional<Vec3> optional1 = this.level.findFreePosition(this, voxelShape1, vec3, dimensions1.width(), dimensions.height(), dimensions1.width());
                if (optional1.isPresent()) {
                    this.setPos(optional1.get().add(0.0, -dimensions.height() / 2.0 + 1.0E-6, 0.0));
                    return true;
                }
            }

            return false;
        }
    }

    public Direction getDirection() {
        return Direction.fromYRot(this.getYRot());
    }

    public Direction getMotionDirection() {
        return this.getDirection();
    }

    protected HoverEvent createHoverEvent() {
        return new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(this.getType(), this.getUUID(), this.getName()));
    }

    public boolean broadcastToPlayer(ServerPlayer player) {
        return true;
    }

    @Override
    public final AABB getBoundingBox() {
        return this.bb;
    }

    public final void setBoundingBox(AABB bb) {
        this.bb = bb;
    }

    public final float getEyeHeight(Pose pose) {
        return this.getDimensions(pose).eyeHeight();
    }

    public final float getEyeHeight() {
        return this.eyeHeight;
    }

    @Override
    public @Nullable SlotAccess getSlot(int slot) {
        return null;
    }

    public InteractionResult interactAt(Player player, Vec3 vec, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    public boolean ignoreExplosion(Explosion explosion) {
        return false;
    }

    public void startSeenByPlayer(ServerPlayer player) {
    }

    public void stopSeenByPlayer(ServerPlayer player) {
    }

    public float rotate(Rotation transformRotation) {
        float f = Mth.wrapDegrees(this.getYRot());

        return switch (transformRotation) {
            case CLOCKWISE_180 -> f + 180.0F;
            case COUNTERCLOCKWISE_90 -> f + 270.0F;
            case CLOCKWISE_90 -> f + 90.0F;
            default -> f;
        };
    }

    public float mirror(Mirror transformMirror) {
        float f = Mth.wrapDegrees(this.getYRot());

        return switch (transformMirror) {
            case FRONT_BACK -> -f;
            case LEFT_RIGHT -> 180.0F - f;
            default -> f;
        };
    }

    public ProjectileDeflection deflection(Projectile projectile) {
        return this.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES) ? ProjectileDeflection.REVERSE : ProjectileDeflection.NONE;
    }

    public @Nullable LivingEntity getControllingPassenger() {
        return null;
    }

    public final boolean hasControllingPassenger() {
        return this.getControllingPassenger() != null;
    }

    public final List<Entity> getPassengers() {
        return this.passengers;
    }

    public @Nullable Entity getFirstPassenger() {
        return this.passengers.isEmpty() ? null : this.passengers.get(0);
    }

    public boolean hasPassenger(Entity entity) {
        return this.passengers.contains(entity);
    }

    public boolean hasPassenger(Predicate<Entity> predicate) {
        for (Entity entity : this.passengers) {
            if (predicate.test(entity)) {
                return true;
            }
        }

        return false;
    }

    private Stream<Entity> getIndirectPassengersStream() {
        return this.passengers.stream().flatMap(Entity::getSelfAndPassengers);
    }

    @Override
    public Stream<Entity> getSelfAndPassengers() {
        return Stream.concat(Stream.of(this), this.getIndirectPassengersStream());
    }

    @Override
    public Stream<Entity> getPassengersAndSelf() {
        return Stream.concat(this.passengers.stream().flatMap(Entity::getPassengersAndSelf), Stream.of(this));
    }

    public Iterable<Entity> getIndirectPassengers() {
        return () -> this.getIndirectPassengersStream().iterator();
    }

    public int countPlayerPassengers() {
        return (int)this.getIndirectPassengersStream().filter(entity -> entity instanceof Player).count();
    }

    public boolean hasExactlyOnePlayerPassenger() {
        return this.countPlayerPassengers() == 1;
    }

    public Entity getRootVehicle() {
        Entity entity = this;

        while (entity.isPassenger()) {
            entity = entity.getVehicle();
        }

        return entity;
    }

    public boolean isPassengerOfSameVehicle(Entity entity) {
        return this.getRootVehicle() == entity.getRootVehicle();
    }

    public boolean hasIndirectPassenger(Entity entity) {
        if (!entity.isPassenger()) {
            return false;
        } else {
            Entity vehicle = entity.getVehicle();
            return vehicle == this || this.hasIndirectPassenger(vehicle);
        }
    }

    public final boolean isLocalInstanceAuthoritative() {
        return this.level.isClientSide() ? this.isLocalClientAuthoritative() : !this.isClientAuthoritative();
    }

    protected boolean isLocalClientAuthoritative() {
        LivingEntity controllingPassenger = this.getControllingPassenger();
        return controllingPassenger != null && controllingPassenger.isLocalClientAuthoritative();
    }

    public boolean isClientAuthoritative() {
        LivingEntity controllingPassenger = this.getControllingPassenger();
        return controllingPassenger != null && controllingPassenger.isClientAuthoritative();
    }

    public boolean canSimulateMovement() {
        return this.isLocalInstanceAuthoritative();
    }

    public boolean isEffectiveAi() {
        return this.isLocalInstanceAuthoritative();
    }

    protected static Vec3 getCollisionHorizontalEscapeVector(double vehicleWidth, double passengerWidth, float yRot) {
        double d = (vehicleWidth + passengerWidth + 1.0E-5F) / 2.0;
        float f = -Mth.sin(yRot * (float) (Math.PI / 180.0));
        float cos = Mth.cos(yRot * (float) (Math.PI / 180.0));
        float max = Math.max(Math.abs(f), Math.abs(cos));
        return new Vec3(f * d / max, 0.0, cos * d / max);
    }

    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return new Vec3(this.getX(), this.getBoundingBox().maxY, this.getZ());
    }

    public @Nullable Entity getVehicle() {
        return this.vehicle;
    }

    public @Nullable Entity getControlledVehicle() {
        return this.vehicle != null && this.vehicle.getControllingPassenger() == this ? this.vehicle : null;
    }

    public PushReaction getPistonPushReaction() {
        return PushReaction.NORMAL;
    }

    public SoundSource getSoundSource() {
        return SoundSource.NEUTRAL;
    }

    public int getFireImmuneTicks() {
        return 0;
    }

    public CommandSourceStack createCommandSourceStackForNameResolution(ServerLevel level) {
        return new CommandSourceStack(
            CommandSource.NULL,
            this.position(),
            this.getRotationVector(),
            level,
            PermissionSet.NO_PERMISSIONS,
            this.getPlainTextName(),
            this.getDisplayName(),
            level.getServer(),
            this
        );
    }

    public void lookAt(EntityAnchorArgument.Anchor anchor, Vec3 target) {
        Vec3 vec3 = anchor.apply(this);
        double d = target.x - vec3.x;
        double d1 = target.y - vec3.y;
        double d2 = target.z - vec3.z;
        double squareRoot = Math.sqrt(d * d + d2 * d2);
        this.setXRot(Mth.wrapDegrees((float)(-(Mth.atan2(d1, squareRoot) * 180.0F / (float)Math.PI))));
        this.setYRot(Mth.wrapDegrees((float)(Mth.atan2(d2, d) * 180.0F / (float)Math.PI) - 90.0F));
        this.setYHeadRot(this.getYRot());
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
    }

    public float getPreciseBodyRotation(float partialTick) {
        return Mth.lerp(partialTick, this.yRotO, this.yRot);
    }

    public boolean updateFluidHeightAndDoFluidPushing(TagKey<Fluid> fluidTag, double motionScale) {
        if (this.touchingUnloadedChunk()) {
            return false;
        } else {
            AABB aabb = this.getBoundingBox().deflate(0.001);
            int floor = Mth.floor(aabb.minX);
            int ceil = Mth.ceil(aabb.maxX);
            int floor1 = Mth.floor(aabb.minY);
            int ceil1 = Mth.ceil(aabb.maxY);
            int floor2 = Mth.floor(aabb.minZ);
            int ceil2 = Mth.ceil(aabb.maxZ);
            double d = 0.0;
            boolean isPushedByFluid = this.isPushedByFluid();
            boolean flag = false;
            Vec3 vec3 = Vec3.ZERO;
            int i = 0;
            BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

            for (int i1 = floor; i1 < ceil; i1++) {
                for (int i2 = floor1; i2 < ceil1; i2++) {
                    for (int i3 = floor2; i3 < ceil2; i3++) {
                        mutableBlockPos.set(i1, i2, i3);
                        FluidState fluidState = this.level().getFluidState(mutableBlockPos);
                        if (fluidState.is(fluidTag)) {
                            double d1 = i2 + fluidState.getHeight(this.level(), mutableBlockPos);
                            if (d1 >= aabb.minY) {
                                flag = true;
                                d = Math.max(d1 - aabb.minY, d);
                                if (isPushedByFluid) {
                                    Vec3 flow = fluidState.getFlow(this.level(), mutableBlockPos);
                                    if (d < 0.4) {
                                        flow = flow.scale(d);
                                    }

                                    vec3 = vec3.add(flow);
                                    i++;
                                }
                            }
                        }
                    }
                }
            }

            if (vec3.length() > 0.0) {
                if (i > 0) {
                    vec3 = vec3.scale(1.0 / i);
                }

                if (!(this instanceof Player)) {
                    vec3 = vec3.normalize();
                }

                Vec3 deltaMovement = this.getDeltaMovement();
                vec3 = vec3.scale(motionScale);
                double d2 = 0.003;
                if (Math.abs(deltaMovement.x) < 0.003 && Math.abs(deltaMovement.z) < 0.003 && vec3.length() < 0.0045000000000000005) {
                    vec3 = vec3.normalize().scale(0.0045000000000000005);
                }

                this.setDeltaMovement(this.getDeltaMovement().add(vec3));
            }

            this.fluidHeight.put(fluidTag, d);
            return flag;
        }
    }

    public boolean touchingUnloadedChunk() {
        AABB aabb = this.getBoundingBox().inflate(1.0);
        int floor = Mth.floor(aabb.minX);
        int ceil = Mth.ceil(aabb.maxX);
        int floor1 = Mth.floor(aabb.minZ);
        int ceil1 = Mth.ceil(aabb.maxZ);
        return !this.level().hasChunksAt(floor, floor1, ceil, ceil1);
    }

    public double getFluidHeight(TagKey<Fluid> fluidTag) {
        return this.fluidHeight.getDouble(fluidTag);
    }

    public double getFluidJumpThreshold() {
        return this.getEyeHeight() < 0.4 ? 0.0 : 0.4;
    }

    public final float getBbWidth() {
        return this.dimensions.width();
    }

    public final float getBbHeight() {
        return this.dimensions.height();
    }

    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        return new ClientboundAddEntityPacket(this, entity);
    }

    public EntityDimensions getDimensions(Pose pose) {
        return this.type.getDimensions();
    }

    public final EntityAttachments getAttachments() {
        return this.dimensions.attachments();
    }

    @Override
    public Vec3 position() {
        return this.position;
    }

    public Vec3 trackingPosition() {
        return this.position();
    }

    @Override
    public BlockPos blockPosition() {
        return this.blockPosition;
    }

    public BlockState getInBlockState() {
        if (this.inBlockState == null) {
            this.inBlockState = this.level().getBlockState(this.blockPosition());
        }

        return this.inBlockState;
    }

    public ChunkPos chunkPosition() {
        return this.chunkPosition;
    }

    public Vec3 getDeltaMovement() {
        return this.deltaMovement;
    }

    public void setDeltaMovement(Vec3 deltaMovement) {
        if (deltaMovement.isFinite()) {
            this.deltaMovement = deltaMovement;
        }
    }

    public void addDeltaMovement(Vec3 addend) {
        if (addend.isFinite()) {
            this.setDeltaMovement(this.getDeltaMovement().add(addend));
        }
    }

    public void setDeltaMovement(double x, double y, double z) {
        this.setDeltaMovement(new Vec3(x, y, z));
    }

    public final int getBlockX() {
        return this.blockPosition.getX();
    }

    public final double getX() {
        return this.position.x;
    }

    public double getX(double scale) {
        return this.position.x + this.getBbWidth() * scale;
    }

    public double getRandomX(double scale) {
        return this.getX((2.0 * this.random.nextDouble() - 1.0) * scale);
    }

    public final int getBlockY() {
        return this.blockPosition.getY();
    }

    public final double getY() {
        return this.position.y;
    }

    public double getY(double scale) {
        return this.position.y + this.getBbHeight() * scale;
    }

    public double getRandomY() {
        return this.getY(this.random.nextDouble());
    }

    public double getEyeY() {
        return this.position.y + this.eyeHeight;
    }

    public final int getBlockZ() {
        return this.blockPosition.getZ();
    }

    public final double getZ() {
        return this.position.z;
    }

    public double getZ(double scale) {
        return this.position.z + this.getBbWidth() * scale;
    }

    public double getRandomZ(double scale) {
        return this.getZ((2.0 * this.random.nextDouble() - 1.0) * scale);
    }

    public final void setPosRaw(double x, double y, double z) {
        if (this.position.x != x || this.position.y != y || this.position.z != z) {
            this.position = new Vec3(x, y, z);
            int floor = Mth.floor(x);
            int floor1 = Mth.floor(y);
            int floor2 = Mth.floor(z);
            if (floor != this.blockPosition.getX() || floor1 != this.blockPosition.getY() || floor2 != this.blockPosition.getZ()) {
                this.blockPosition = new BlockPos(floor, floor1, floor2);
                this.inBlockState = null;
                if (SectionPos.blockToSectionCoord(floor) != this.chunkPosition.x || SectionPos.blockToSectionCoord(floor2) != this.chunkPosition.z) {
                    this.chunkPosition = new ChunkPos(this.blockPosition);
                }
            }

            this.levelCallback.onMove();
            if (!this.firstTick && this.level instanceof ServerLevel serverLevel && !this.isRemoved()) {
                if (this instanceof WaypointTransmitter waypointTransmitter && waypointTransmitter.isTransmittingWaypoint()) {
                    serverLevel.getWaypointManager().updateWaypoint(waypointTransmitter);
                }

                if (this instanceof ServerPlayer serverPlayer && serverPlayer.isReceivingWaypoints() && serverPlayer.connection != null) {
                    serverLevel.getWaypointManager().updatePlayer(serverPlayer);
                }
            }
        }
    }

    public void checkDespawn() {
    }

    public Vec3[] getQuadLeashHolderOffsets() {
        return Leashable.createQuadLeashOffsets(this, 0.0, 0.5, 0.5, 0.0);
    }

    public boolean supportQuadLeashAsHolder() {
        return false;
    }

    public void notifyLeashHolder(Leashable leashHolder) {
    }

    public void notifyLeasheeRemoved(Leashable leashHolder) {
    }

    public Vec3 getRopeHoldPosition(float partialTick) {
        return this.getPosition(partialTick).add(0.0, this.eyeHeight * 0.7, 0.0);
    }

    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        int id = packet.getId();
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        this.syncPacketPositionCodec(x, y, z);
        this.snapTo(x, y, z, packet.getYRot(), packet.getXRot());
        this.setId(id);
        this.setUUID(packet.getUUID());
        this.setDeltaMovement(packet.getMovement());
    }

    public @Nullable ItemStack getPickResult() {
        return null;
    }

    public void setIsInPowderSnow(boolean isInPowderSnow) {
        this.isInPowderSnow = isInPowderSnow;
    }

    public boolean canFreeze() {
        return !this.getType().is(EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES);
    }

    public boolean isFreezing() {
        return this.getTicksFrozen() > 0;
    }

    public float getYRot() {
        return this.yRot;
    }

    @Override
    public float getVisualRotationYInDegrees() {
        return this.getYRot();
    }

    public void setYRot(float yRot) {
        if (!Float.isFinite(yRot)) {
            Util.logAndPauseIfInIde("Invalid entity rotation: " + yRot + ", discarding.");
        } else {
            this.yRot = yRot;
        }
    }

    public float getXRot() {
        return this.xRot;
    }

    public void setXRot(float xRot) {
        if (!Float.isFinite(xRot)) {
            Util.logAndPauseIfInIde("Invalid entity rotation: " + xRot + ", discarding.");
        } else {
            this.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    public boolean canSprint() {
        return false;
    }

    public float maxUpStep() {
        return 0.0F;
    }

    public void onExplosionHit(@Nullable Entity entity) {
    }

    @Override
    public final boolean isRemoved() {
        return this.removalReason != null;
    }

    public Entity.@Nullable RemovalReason getRemovalReason() {
        return this.removalReason;
    }

    @Override
    public final void setRemoved(Entity.RemovalReason removalReason) {
        if (this.removalReason == null) {
            this.removalReason = removalReason;
        }

        if (this.removalReason.shouldDestroy()) {
            this.stopRiding();
        }

        this.getPassengers().forEach(Entity::stopRiding);
        this.levelCallback.onRemove(removalReason);
        this.onRemoval(removalReason);
    }

    public void unsetRemoved() {
        this.removalReason = null;
    }

    @Override
    public void setLevelCallback(EntityInLevelCallback levelCallback) {
        this.levelCallback = levelCallback;
    }

    @Override
    public boolean shouldBeSaved() {
        return (this.removalReason == null || this.removalReason.shouldSave())
            && !this.isPassenger()
            && (!this.isVehicle() || !this.hasExactlyOnePlayerPassenger());
    }

    @Override
    public boolean isAlwaysTicking() {
        return false;
    }

    public boolean mayInteract(ServerLevel level, BlockPos pos) {
        return true;
    }

    public boolean isFlyingVehicle() {
        return false;
    }

    @Override
    public Level level() {
        return this.level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public DamageSources damageSources() {
        return this.level().damageSources();
    }

    public RegistryAccess registryAccess() {
        return this.level().registryAccess();
    }

    protected void lerpPositionAndRotationStep(int steps, double targetX, double targetY, double targetZ, double targetYRot, double targetXRot) {
        double d = 1.0 / steps;
        double d1 = Mth.lerp(d, this.getX(), targetX);
        double d2 = Mth.lerp(d, this.getY(), targetY);
        double d3 = Mth.lerp(d, this.getZ(), targetZ);
        float f = (float)Mth.rotLerp(d, (double)this.getYRot(), targetYRot);
        float f1 = (float)Mth.lerp(d, (double)this.getXRot(), targetXRot);
        this.setPos(d1, d2, d3);
        this.setRot(f, f1);
    }

    public RandomSource getRandom() {
        return this.random;
    }

    public Vec3 getKnownMovement() {
        return this.getControllingPassenger() instanceof Player player && this.isAlive() ? player.getKnownMovement() : this.getDeltaMovement();
    }

    public Vec3 getKnownSpeed() {
        return this.getControllingPassenger() instanceof Player player && this.isAlive() ? player.getKnownSpeed() : this.lastKnownSpeed;
    }

    public @Nullable ItemStack getWeaponItem() {
        return null;
    }

    public Optional<ResourceKey<LootTable>> getLootTable() {
        return this.type.getDefaultLootTable();
    }

    protected void applyImplicitComponents(DataComponentGetter componentGetter) {
        this.applyImplicitComponentIfPresent(componentGetter, DataComponents.CUSTOM_NAME);
        this.applyImplicitComponentIfPresent(componentGetter, DataComponents.CUSTOM_DATA);
    }

    public final void applyComponentsFromItemStack(ItemStack stack) {
        this.applyImplicitComponents(stack.getComponents());
    }

    @Override
    public <T> @Nullable T get(DataComponentType<? extends T> component) {
        if (component == DataComponents.CUSTOM_NAME) {
            return castComponentValue((DataComponentType<T>)component, this.getCustomName());
        } else {
            return component == DataComponents.CUSTOM_DATA ? castComponentValue((DataComponentType<T>)component, this.customData) : null;
        }
    }

    @Contract("_,!null->!null;_,_->_")
    protected static <T> @Nullable T castComponentValue(DataComponentType<T> component, @Nullable Object value) {
        return (T)value;
    }

    public <T> void setComponent(DataComponentType<T> component, T value) {
        this.applyImplicitComponent(component, value);
    }

    protected <T> boolean applyImplicitComponent(DataComponentType<T> component, T value) {
        if (component == DataComponents.CUSTOM_NAME) {
            this.setCustomName(castComponentValue(DataComponents.CUSTOM_NAME, value));
            return true;
        } else if (component == DataComponents.CUSTOM_DATA) {
            this.customData = castComponentValue(DataComponents.CUSTOM_DATA, value);
            return true;
        } else {
            return false;
        }
    }

    protected <T> boolean applyImplicitComponentIfPresent(DataComponentGetter componentGetter, DataComponentType<T> component) {
        T object = componentGetter.get(component);
        return object != null && this.applyImplicitComponent(component, object);
    }

    public ProblemReporter.PathElement problemPath() {
        return new Entity.EntityPathElement(this);
    }

    @Override
    public void registerDebugValues(ServerLevel level, DebugValueSource.Registration registrar) {
    }

    record EntityPathElement(Entity entity) implements ProblemReporter.PathElement {
        @Override
        public String get() {
            return this.entity.toString();
        }
    }

    @FunctionalInterface
    public interface MoveFunction {
        void accept(Entity entity, double x, double y, double z);
    }

    record Movement(Vec3 from, Vec3 to, Optional<Vec3> axisDependentOriginalMovement) {
        public Movement(Vec3 from, Vec3 to, Vec3 axisDependentOriginalMovement) {
            this(from, to, Optional.of(axisDependentOriginalMovement));
        }

        public Movement(Vec3 from, Vec3 to) {
            this(from, to, Optional.empty());
        }
    }

    public static enum MovementEmission {
        NONE(false, false),
        SOUNDS(true, false),
        EVENTS(false, true),
        ALL(true, true);

        final boolean sounds;
        final boolean events;

        private MovementEmission(final boolean sounds, final boolean events) {
            this.sounds = sounds;
            this.events = events;
        }

        public boolean emitsAnything() {
            return this.events || this.sounds;
        }

        public boolean emitsEvents() {
            return this.events;
        }

        public boolean emitsSounds() {
            return this.sounds;
        }
    }

    public static enum RemovalReason {
        KILLED(true, false),
        DISCARDED(true, false),
        UNLOADED_TO_CHUNK(false, true),
        UNLOADED_WITH_PLAYER(false, false),
        CHANGED_DIMENSION(false, false);

        private final boolean destroy;
        private final boolean save;

        private RemovalReason(final boolean destroy, final boolean save) {
            this.destroy = destroy;
            this.save = save;
        }

        public boolean shouldDestroy() {
            return this.destroy;
        }

        public boolean shouldSave() {
            return this.save;
        }
    }
}
