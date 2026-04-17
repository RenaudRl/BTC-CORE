package net.minecraft.world.entity.projectile;

import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.List;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class FishingHook extends Projectile {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final RandomSource syncronizedRandom = RandomSource.create();
    private boolean biting;
    public int outOfWaterTime;
    private static final int MAX_OUT_OF_WATER_TIME = 10;
    public static final EntityDataAccessor<Integer> DATA_HOOKED_ENTITY = SynchedEntityData.defineId(FishingHook.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_BITING = SynchedEntityData.defineId(FishingHook.class, EntityDataSerializers.BOOLEAN);
    private int life;
    private int nibble;
    public int timeUntilLured;
    public int timeUntilHooked;
    public float fishAngle;
    private boolean openWater = true;
    public @Nullable Entity hookedIn;
    public FishingHook.FishHookState currentState = FishingHook.FishHookState.FLYING;
    private final int luck;
    private final int lureSpeed;
    private final InterpolationHandler interpolationHandler = new InterpolationHandler(this);

    private FishingHook(EntityType<? extends FishingHook> type, Level level, int luck, int lureSpeed) {
        super(type, level);
        this.luck = Math.max(0, luck);
        this.lureSpeed = Math.max(0, lureSpeed);
    }

    public FishingHook(EntityType<? extends FishingHook> type, Level level) {
        this(type, level, 0, 0);
    }

    public FishingHook(Player owner, Level level, int luck, int lureSpeed) {
        this(EntityType.FISHING_BOBBER, level, luck, lureSpeed);
        this.setOwner(owner);
        float xRot = owner.getXRot();
        float yRot = owner.getYRot();
        float cos = Mth.cos(-yRot * (float) (Math.PI / 180.0) - (float) Math.PI);
        float sin = Mth.sin(-yRot * (float) (Math.PI / 180.0) - (float) Math.PI);
        float f = -Mth.cos(-xRot * (float) (Math.PI / 180.0));
        float sin1 = Mth.sin(-xRot * (float) (Math.PI / 180.0));
        double d = owner.getX() - sin * 0.3;
        double eyeY = owner.getEyeY();
        double d1 = owner.getZ() - cos * 0.3;
        this.snapTo(d, eyeY, d1, yRot, xRot);
        Vec3 vec3 = new Vec3(-sin, Mth.clamp(-(sin1 / f), -5.0F, 5.0F), -cos);
        double len = vec3.length();
        vec3 = vec3.multiply(
            0.6 / len + this.random.triangle(0.5, 0.0103365),
            0.6 / len + this.random.triangle(0.5, 0.0103365),
            0.6 / len + this.random.triangle(0.5, 0.0103365)
        );
        this.setDeltaMovement(vec3);
        this.setYRot((float)(Mth.atan2(vec3.x, vec3.z) * 180.0F / (float)Math.PI));
        this.setXRot((float)(Mth.atan2(vec3.y, vec3.horizontalDistance()) * 180.0F / (float)Math.PI));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return this.interpolationHandler;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HOOKED_ENTITY, 0);
        builder.define(DATA_BITING, false);
    }

    @Override
    protected boolean shouldBounceOnWorldBorder() {
        return true;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_HOOKED_ENTITY.equals(key)) {
            int i = this.getEntityData().get(DATA_HOOKED_ENTITY);
            this.hookedIn = i > 0 ? this.level().getEntity(i - 1) : null;
        }

        if (DATA_BITING.equals(key)) {
            this.biting = this.getEntityData().get(DATA_BITING);
            if (this.biting) {
                this.setDeltaMovement(this.getDeltaMovement().x, -0.4F * Mth.nextFloat(this.syncronizedRandom, 0.6F, 1.0F), this.getDeltaMovement().z);
            }
        }

        super.onSyncedDataUpdated(key);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double d = 64.0;
        return distance < 4096.0;
    }

    @Override
    public void tick() {
        this.syncronizedRandom.setSeed(this.getUUID().getLeastSignificantBits() ^ this.level().getGameTime());
        this.getInterpolation().interpolate();
        super.tick();
        Player playerOwner = this.getPlayerOwner();
        if (playerOwner == null) {
            this.discard();
        } else if (this.level().isClientSide() || !this.shouldStopFishing(playerOwner)) {
            if (this.onGround()) {
                this.life++;
                if (this.life >= 1200) {
                    this.discard();
                    return;
                }
            } else {
                this.life = 0;
            }

            float f = 0.0F;
            BlockPos blockPos = this.blockPosition();
            FluidState fluidState = this.level().getFluidState(blockPos);
            if (fluidState.is(FluidTags.WATER)) {
                f = fluidState.getHeight(this.level(), blockPos);
            }

            boolean flag = f > 0.0F;
            if (this.currentState == FishingHook.FishHookState.FLYING) {
                if (this.hookedIn != null) {
                    this.setDeltaMovement(Vec3.ZERO);
                    this.currentState = FishingHook.FishHookState.HOOKED_IN_ENTITY;
                    return;
                }

                if (flag) {
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.3, 0.2, 0.3));
                    this.currentState = FishingHook.FishHookState.BOBBING;
                    return;
                }

                this.checkCollision();
            } else {
                if (this.currentState == FishingHook.FishHookState.HOOKED_IN_ENTITY) {
                    if (this.hookedIn != null) {
                        if (!this.hookedIn.isRemoved() && this.hookedIn.canInteractWithLevel() && this.hookedIn.level().dimension() == this.level().dimension()
                            )
                         {
                            this.setPos(this.hookedIn.getX(), this.hookedIn.getY(0.8), this.hookedIn.getZ());
                        } else {
                            this.setHookedEntity(null);
                            this.currentState = FishingHook.FishHookState.FLYING;
                        }
                    }

                    return;
                }

                if (this.currentState == FishingHook.FishHookState.BOBBING) {
                    Vec3 deltaMovement = this.getDeltaMovement();
                    double d = this.getY() + deltaMovement.y - blockPos.getY() - f;
                    if (Math.abs(d) < 0.01) {
                        d += Math.signum(d) * 0.1;
                    }

                    this.setDeltaMovement(deltaMovement.x * 0.9, deltaMovement.y - d * this.random.nextFloat() * 0.2, deltaMovement.z * 0.9);
                    if (this.nibble <= 0 && this.timeUntilHooked <= 0) {
                        this.openWater = true;
                    } else {
                        this.openWater = this.openWater && this.outOfWaterTime < 10 && this.calculateOpenWater(blockPos);
                    }

                    if (flag) {
                        this.outOfWaterTime = Math.max(0, this.outOfWaterTime - 1);
                        if (this.biting) {
                            this.setDeltaMovement(
                                this.getDeltaMovement().add(0.0, -0.1 * this.syncronizedRandom.nextFloat() * this.syncronizedRandom.nextFloat(), 0.0)
                            );
                        }

                        if (!this.level().isClientSide()) {
                            this.catchingFish(blockPos);
                        }
                    } else {
                        this.outOfWaterTime = Math.min(10, this.outOfWaterTime + 1);
                    }
                }
            }

            if (!fluidState.is(FluidTags.WATER) && !this.onGround() && this.hookedIn == null) {
                this.setDeltaMovement(this.getDeltaMovement().add(0.0, -0.03, 0.0));
            }

            this.move(MoverType.SELF, this.getDeltaMovement());
            this.applyEffectsFromBlocks();
            this.updateRotation();
            if (this.currentState == FishingHook.FishHookState.FLYING && (this.onGround() || this.horizontalCollision)) {
                this.setDeltaMovement(Vec3.ZERO);
            }

            double d1 = 0.92;
            this.setDeltaMovement(this.getDeltaMovement().scale(0.92));
            this.reapplyPosition();
        }
    }

    private boolean shouldStopFishing(Player player) {
        if (player.canInteractWithLevel()) {
            ItemStack mainHandItem = player.getMainHandItem();
            ItemStack offhandItem = player.getOffhandItem();
            boolean isFishingRod = mainHandItem.is(Items.FISHING_ROD);
            boolean isFishingRod1 = offhandItem.is(Items.FISHING_ROD);
            if ((isFishingRod || isFishingRod1) && this.distanceToSqr(player) <= 1024.0) {
                return false;
            }
        }

        this.discard();
        return true;
    }

    private void checkCollision() {
        HitResult hitResultOnMoveVector = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        this.hitTargetOrDeflectSelf(hitResultOnMoveVector);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) || target.isAlive() && target instanceof ItemEntity;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!this.level().isClientSide()) {
            this.setHookedEntity(result.getEntity());
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        this.setDeltaMovement(this.getDeltaMovement().normalize().scale(result.distanceTo(this)));
    }

    public void setHookedEntity(@Nullable Entity hookedEntity) {
        this.hookedIn = hookedEntity;
        this.getEntityData().set(DATA_HOOKED_ENTITY, hookedEntity == null ? 0 : hookedEntity.getId() + 1);
    }

    private void catchingFish(BlockPos pos) {
        ServerLevel serverLevel = (ServerLevel)this.level();
        int i = 1;
        BlockPos blockPos = pos.above();
        if (this.random.nextFloat() < 0.25F && this.level().isRainingAt(blockPos)) {
            i++;
        }

        if (this.random.nextFloat() < 0.5F && !this.level().canSeeSky(blockPos)) {
            i--;
        }

        if (this.nibble > 0) {
            this.nibble--;
            if (this.nibble <= 0) {
                this.timeUntilLured = 0;
                this.timeUntilHooked = 0;
                this.getEntityData().set(DATA_BITING, false);
            }
        } else if (this.timeUntilHooked > 0) {
            this.timeUntilHooked -= i;
            if (this.timeUntilHooked > 0) {
                this.fishAngle = this.fishAngle + (float)this.random.triangle(0.0, 9.188);
                float f = this.fishAngle * (float) (Math.PI / 180.0);
                float sin = Mth.sin(f);
                float cos = Mth.cos(f);
                double d = this.getX() + sin * this.timeUntilHooked * 0.1F;
                double d1 = Mth.floor(this.getY()) + 1.0F;
                double d2 = this.getZ() + cos * this.timeUntilHooked * 0.1F;
                BlockState blockState = serverLevel.getBlockState(BlockPos.containing(d, d1 - 1.0, d2));
                if (blockState.is(Blocks.WATER)) {
                    if (this.random.nextFloat() < 0.15F) {
                        serverLevel.sendParticles(ParticleTypes.BUBBLE, d, d1 - 0.1F, d2, 1, sin, 0.1, cos, 0.0);
                    }

                    float f1 = sin * 0.04F;
                    float f2 = cos * 0.04F;
                    serverLevel.sendParticles(ParticleTypes.FISHING, d, d1, d2, 0, f2, 0.01, -f1, 1.0);
                    serverLevel.sendParticles(ParticleTypes.FISHING, d, d1, d2, 0, -f2, 0.01, f1, 1.0);
                }
            } else {
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
                double d3 = this.getY() + 0.5;
                serverLevel.sendParticles(
                    ParticleTypes.BUBBLE,
                    this.getX(),
                    d3,
                    this.getZ(),
                    (int)(1.0F + this.getBbWidth() * 20.0F),
                    this.getBbWidth(),
                    0.0,
                    this.getBbWidth(),
                    0.2F
                );
                serverLevel.sendParticles(
                    ParticleTypes.FISHING,
                    this.getX(),
                    d3,
                    this.getZ(),
                    (int)(1.0F + this.getBbWidth() * 20.0F),
                    this.getBbWidth(),
                    0.0,
                    this.getBbWidth(),
                    0.2F
                );
                this.nibble = Mth.nextInt(this.random, 20, 40);
                this.getEntityData().set(DATA_BITING, true);
            }
        } else if (this.timeUntilLured > 0) {
            this.timeUntilLured -= i;
            float f = 0.15F;
            if (this.timeUntilLured < 20) {
                f += (20 - this.timeUntilLured) * 0.05F;
            } else if (this.timeUntilLured < 40) {
                f += (40 - this.timeUntilLured) * 0.02F;
            } else if (this.timeUntilLured < 60) {
                f += (60 - this.timeUntilLured) * 0.01F;
            }

            if (this.random.nextFloat() < f) {
                float sin = Mth.nextFloat(this.random, 0.0F, 360.0F) * (float) (Math.PI / 180.0);
                float cos = Mth.nextFloat(this.random, 25.0F, 60.0F);
                double d = this.getX() + Mth.sin(sin) * cos * 0.1;
                double d1 = Mth.floor(this.getY()) + 1.0F;
                double d2 = this.getZ() + Mth.cos(sin) * cos * 0.1;
                BlockState blockState = serverLevel.getBlockState(BlockPos.containing(d, d1 - 1.0, d2));
                if (blockState.is(Blocks.WATER)) {
                    serverLevel.sendParticles(ParticleTypes.SPLASH, d, d1, d2, 2 + this.random.nextInt(2), 0.1F, 0.0, 0.1F, 0.0);
                }
            }

            if (this.timeUntilLured <= 0) {
                this.fishAngle = Mth.nextFloat(this.random, 0.0F, 360.0F);
                this.timeUntilHooked = Mth.nextInt(this.random, 20, 80);
            }
        } else {
            this.timeUntilLured = Mth.nextInt(this.random, 100, 600);
            this.timeUntilLured = this.timeUntilLured - this.lureSpeed;
        }
    }

    public boolean calculateOpenWater(BlockPos pos) {
        FishingHook.OpenWaterType openWaterType = FishingHook.OpenWaterType.INVALID;

        for (int i = -1; i <= 2; i++) {
            FishingHook.OpenWaterType openWaterTypeForArea = this.getOpenWaterTypeForArea(pos.offset(-2, i, -2), pos.offset(2, i, 2));
            switch (openWaterTypeForArea) {
                case ABOVE_WATER:
                    if (openWaterType == FishingHook.OpenWaterType.INVALID) {
                        return false;
                    }
                    break;
                case INSIDE_WATER:
                    if (openWaterType == FishingHook.OpenWaterType.ABOVE_WATER) {
                        return false;
                    }
                    break;
                case INVALID:
                    return false;
            }

            openWaterType = openWaterTypeForArea;
        }

        return true;
    }

    private FishingHook.OpenWaterType getOpenWaterTypeForArea(BlockPos pos1, BlockPos pos2) {
        return BlockPos.betweenClosedStream(pos1, pos2)
            .map(this::getOpenWaterTypeForBlock)
            .reduce((type1, type2) -> type1 == type2 ? type1 : FishingHook.OpenWaterType.INVALID)
            .orElse(FishingHook.OpenWaterType.INVALID);
    }

    private FishingHook.OpenWaterType getOpenWaterTypeForBlock(BlockPos pos) {
        BlockState blockState = this.level().getBlockState(pos);
        if (!blockState.isAir() && !blockState.is(Blocks.LILY_PAD)) {
            FluidState fluidState = blockState.getFluidState();
            return fluidState.is(FluidTags.WATER) && fluidState.isSource() && blockState.getCollisionShape(this.level(), pos).isEmpty()
                ? FishingHook.OpenWaterType.INSIDE_WATER
                : FishingHook.OpenWaterType.INVALID;
        } else {
            return FishingHook.OpenWaterType.ABOVE_WATER;
        }
    }

    public boolean isOpenWaterFishing() {
        return this.openWater;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    public int retrieve(ItemStack stack) {
        Player playerOwner = this.getPlayerOwner();
        if (!this.level().isClientSide() && playerOwner != null && !this.shouldStopFishing(playerOwner)) {
            int i = 0;
            if (this.hookedIn != null) {
                this.pullEntity(this.hookedIn);
                CriteriaTriggers.FISHING_ROD_HOOKED.trigger((ServerPlayer)playerOwner, stack, this, Collections.emptyList());
                this.level().broadcastEntityEvent(this, EntityEvent.FISHING_ROD_REEL_IN);
                i = this.hookedIn instanceof ItemEntity ? 3 : 5;
            } else if (this.nibble > 0) {
                LootParams lootParams = new LootParams.Builder((ServerLevel)this.level())
                    .withParameter(LootContextParams.ORIGIN, this.position())
                    .withParameter(LootContextParams.TOOL, stack)
                    .withParameter(LootContextParams.THIS_ENTITY, this)
                    .withLuck(this.luck + playerOwner.getLuck())
                    .create(LootContextParamSets.FISHING);
                LootTable lootTable = this.level().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
                List<ItemStack> randomItems = lootTable.getRandomItems(lootParams);
                CriteriaTriggers.FISHING_ROD_HOOKED.trigger((ServerPlayer)playerOwner, stack, this, randomItems);

                for (ItemStack itemStack : randomItems) {
                    ItemEntity itemEntity = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), itemStack);
                    double d = playerOwner.getX() - this.getX();
                    double d1 = playerOwner.getY() - this.getY();
                    double d2 = playerOwner.getZ() - this.getZ();
                    double d3 = 0.1;
                    itemEntity.setDeltaMovement(d * 0.1, d1 * 0.1 + Math.sqrt(Math.sqrt(d * d + d1 * d1 + d2 * d2)) * 0.08, d2 * 0.1);
                    this.level().addFreshEntity(itemEntity);
                    playerOwner.level()
                        .addFreshEntity(
                            new ExperienceOrb(
                                playerOwner.level(), playerOwner.getX(), playerOwner.getY() + 0.5, playerOwner.getZ() + 0.5, this.random.nextInt(6) + 1
                            )
                        );
                    if (itemStack.is(ItemTags.FISHES)) {
                        playerOwner.awardStat(Stats.FISH_CAUGHT, 1);
                    }
                }

                i = 1;
            }

            if (this.onGround()) {
                i = 2;
            }

            this.discard();
            return i;
        } else {
            return 0;
        }
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EntityEvent.FISHING_ROD_REEL_IN && this.level().isClientSide() && this.hookedIn instanceof Player player && player.isLocalPlayer()) {
            this.pullEntity(this.hookedIn);
        }

        super.handleEntityEvent(id);
    }

    public void pullEntity(Entity entity) {
        Entity owner = this.getOwner();
        if (owner != null) {
            Vec3 vec3 = new Vec3(owner.getX() - this.getX(), owner.getY() - this.getY(), owner.getZ() - this.getZ()).scale(0.1);
            entity.setDeltaMovement(entity.getDeltaMovement().add(vec3));
        }
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        this.updateOwnerInfo(null);
        super.remove(reason);
    }

    @Override
    public void onClientRemoval() {
        this.updateOwnerInfo(null);
    }

    @Override
    public void setOwner(@Nullable Entity owner) {
        super.setOwner(owner);
        this.updateOwnerInfo(this);
    }

    private void updateOwnerInfo(@Nullable FishingHook fishingHook) {
        Player playerOwner = this.getPlayerOwner();
        if (playerOwner != null) {
            playerOwner.fishing = fishingHook;
        }
    }

    public @Nullable Player getPlayerOwner() {
        return this.getOwner() instanceof Player player ? player : null;
    }

    public @Nullable Entity getHookedIn() {
        return this.hookedIn;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        Entity owner = this.getOwner();
        return new ClientboundAddEntityPacket(this, entity, owner == null ? this.getId() : owner.getId());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        if (this.getPlayerOwner() == null) {
            int data = packet.getData();
            LOGGER.error("Failed to recreate fishing hook on client. {} (id: {}) is not a valid owner.", this.level().getEntity(data), data);
            this.discard();
        }
    }

    public static enum FishHookState {
        FLYING,
        HOOKED_IN_ENTITY,
        BOBBING;
    }

    static enum OpenWaterType {
        ABOVE_WATER,
        INSIDE_WATER,
        INVALID;
    }
}
