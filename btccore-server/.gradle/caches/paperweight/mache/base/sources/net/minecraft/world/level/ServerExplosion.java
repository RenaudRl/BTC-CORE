package net.minecraft.world.level;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class ServerExplosion implements Explosion {
    private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    private final boolean fire;
    private final Explosion.BlockInteraction blockInteraction;
    private final ServerLevel level;
    private final Vec3 center;
    private final @Nullable Entity source;
    private final float radius;
    private final DamageSource damageSource;
    private final ExplosionDamageCalculator damageCalculator;
    private final Map<Player, Vec3> hitPlayers = new HashMap<>();

    public ServerExplosion(
        ServerLevel level,
        @Nullable Entity source,
        @Nullable DamageSource damageSource,
        @Nullable ExplosionDamageCalculator damageCalculator,
        Vec3 center,
        float radius,
        boolean fire,
        Explosion.BlockInteraction blockInteraction
    ) {
        this.level = level;
        this.source = source;
        this.radius = radius;
        this.center = center;
        this.fire = fire;
        this.blockInteraction = blockInteraction;
        this.damageSource = damageSource == null ? level.damageSources().explosion(this) : damageSource;
        this.damageCalculator = damageCalculator == null ? this.makeDamageCalculator(source) : damageCalculator;
    }

    private ExplosionDamageCalculator makeDamageCalculator(@Nullable Entity entity) {
        return (ExplosionDamageCalculator)(entity == null ? EXPLOSION_DAMAGE_CALCULATOR : new EntityBasedExplosionDamageCalculator(entity));
    }

    public static float getSeenPercent(Vec3 explosionVector, Entity entity) {
        AABB boundingBox = entity.getBoundingBox();
        double d = 1.0 / ((boundingBox.maxX - boundingBox.minX) * 2.0 + 1.0);
        double d1 = 1.0 / ((boundingBox.maxY - boundingBox.minY) * 2.0 + 1.0);
        double d2 = 1.0 / ((boundingBox.maxZ - boundingBox.minZ) * 2.0 + 1.0);
        double d3 = (1.0 - Math.floor(1.0 / d) * d) / 2.0;
        double d4 = (1.0 - Math.floor(1.0 / d2) * d2) / 2.0;
        if (!(d < 0.0) && !(d1 < 0.0) && !(d2 < 0.0)) {
            int i = 0;
            int i1 = 0;

            for (double d5 = 0.0; d5 <= 1.0; d5 += d) {
                for (double d6 = 0.0; d6 <= 1.0; d6 += d1) {
                    for (double d7 = 0.0; d7 <= 1.0; d7 += d2) {
                        double d8 = Mth.lerp(d5, boundingBox.minX, boundingBox.maxX);
                        double d9 = Mth.lerp(d6, boundingBox.minY, boundingBox.maxY);
                        double d10 = Mth.lerp(d7, boundingBox.minZ, boundingBox.maxZ);
                        Vec3 vec3 = new Vec3(d8 + d3, d9, d10 + d4);
                        if (entity.level().clip(new ClipContext(vec3, explosionVector, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType()
                            == HitResult.Type.MISS) {
                            i++;
                        }

                        i1++;
                    }
                }
            }

            return (float)i / i1;
        } else {
            return 0.0F;
        }
    }

    @Override
    public float radius() {
        return this.radius;
    }

    @Override
    public Vec3 center() {
        return this.center;
    }

    private List<BlockPos> calculateExplodedPositions() {
        Set<BlockPos> set = new HashSet<>();
        int i = 16;

        for (int i1 = 0; i1 < 16; i1++) {
            for (int i2 = 0; i2 < 16; i2++) {
                for (int i3 = 0; i3 < 16; i3++) {
                    if (i1 == 0 || i1 == 15 || i2 == 0 || i2 == 15 || i3 == 0 || i3 == 15) {
                        double d = i1 / 15.0F * 2.0F - 1.0F;
                        double d1 = i2 / 15.0F * 2.0F - 1.0F;
                        double d2 = i3 / 15.0F * 2.0F - 1.0F;
                        double squareRoot = Math.sqrt(d * d + d1 * d1 + d2 * d2);
                        d /= squareRoot;
                        d1 /= squareRoot;
                        d2 /= squareRoot;
                        float f = this.radius * (0.7F + this.level.random.nextFloat() * 0.6F);
                        double d3 = this.center.x;
                        double d4 = this.center.y;
                        double d5 = this.center.z;

                        for (float f1 = 0.3F; f > 0.0F; f -= 0.22500001F) {
                            BlockPos blockPos = BlockPos.containing(d3, d4, d5);
                            BlockState blockState = this.level.getBlockState(blockPos);
                            FluidState fluidState = this.level.getFluidState(blockPos);
                            if (!this.level.isInWorldBounds(blockPos)) {
                                break;
                            }

                            Optional<Float> blockExplosionResistance = this.damageCalculator
                                .getBlockExplosionResistance(this, this.level, blockPos, blockState, fluidState);
                            if (blockExplosionResistance.isPresent()) {
                                f -= (blockExplosionResistance.get() + 0.3F) * 0.3F;
                            }

                            if (f > 0.0F && this.damageCalculator.shouldBlockExplode(this, this.level, blockPos, blockState, f)) {
                                set.add(blockPos);
                            }

                            d3 += d * 0.3F;
                            d4 += d1 * 0.3F;
                            d5 += d2 * 0.3F;
                        }
                    }
                }
            }
        }

        return new ObjectArrayList<>(set);
    }

    private void hurtEntities() {
        if (!(this.radius < 1.0E-5F)) {
            float f = this.radius * 2.0F;
            int floor = Mth.floor(this.center.x - f - 1.0);
            int floor1 = Mth.floor(this.center.x + f + 1.0);
            int floor2 = Mth.floor(this.center.y - f - 1.0);
            int floor3 = Mth.floor(this.center.y + f + 1.0);
            int floor4 = Mth.floor(this.center.z - f - 1.0);
            int floor5 = Mth.floor(this.center.z + f + 1.0);

            for (Entity entity : this.level.getEntities(this.source, new AABB(floor, floor2, floor4, floor1, floor3, floor5))) {
                if (!entity.ignoreExplosion(this)) {
                    double d = Math.sqrt(entity.distanceToSqr(this.center)) / f;
                    if (!(d > 1.0)) {
                        Vec3 vec3 = entity instanceof PrimedTnt ? entity.position() : entity.getEyePosition();
                        Vec3 vec31 = vec3.subtract(this.center).normalize();
                        boolean shouldDamageEntity = this.damageCalculator.shouldDamageEntity(this, entity);
                        float knockbackMultiplier = this.damageCalculator.getKnockbackMultiplier(entity);
                        float f1 = !shouldDamageEntity && knockbackMultiplier == 0.0F ? 0.0F : getSeenPercent(this.center, entity);
                        if (shouldDamageEntity) {
                            entity.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, entity, f1));
                        }

                        double d1 = entity instanceof LivingEntity livingEntity
                            ? livingEntity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                            : 0.0;
                        double d2 = (1.0 - d) * f1 * knockbackMultiplier * (1.0 - d1);
                        Vec3 vec32 = vec31.scale(d2);
                        entity.push(vec32);
                        if (entity.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof Projectile projectile) {
                            projectile.setOwner(this.damageSource.getEntity());
                        } else if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                            this.hitPlayers.put(player, vec32);
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }
    }

    private void interactWithBlocks(List<BlockPos> blocks) {
        List<ServerExplosion.StackCollector> list = new ArrayList<>();
        Util.shuffle(blocks, this.level.random);

        for (BlockPos blockPos : blocks) {
            this.level
                .getBlockState(blockPos)
                .onExplosionHit(this.level, blockPos, this, (itemStack, blockPos1) -> addOrAppendStack(list, itemStack, blockPos1));
        }

        for (ServerExplosion.StackCollector stackCollector : list) {
            Block.popResource(this.level, stackCollector.pos, stackCollector.stack);
        }
    }

    private void createFire(List<BlockPos> blocks) {
        for (BlockPos blockPos : blocks) {
            if (this.level.random.nextInt(3) == 0 && this.level.getBlockState(blockPos).isAir() && this.level.getBlockState(blockPos.below()).isSolidRender()) {
                this.level.setBlockAndUpdate(blockPos, BaseFireBlock.getState(this.level, blockPos));
            }
        }
    }

    public int explode() {
        this.level.gameEvent(this.source, GameEvent.EXPLODE, this.center);
        List<BlockPos> list = this.calculateExplodedPositions();
        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            ProfilerFiller profilerFiller = Profiler.get();
            profilerFiller.push("explosion_blocks");
            this.interactWithBlocks(list);
            profilerFiller.pop();
        }

        if (this.fire) {
            this.createFire(list);
        }

        return list.size();
    }

    private static void addOrAppendStack(List<ServerExplosion.StackCollector> stackCollectors, ItemStack stack, BlockPos pos) {
        for (ServerExplosion.StackCollector stackCollector : stackCollectors) {
            stackCollector.tryMerge(stack);
            if (stack.isEmpty()) {
                return;
            }
        }

        stackCollectors.add(new ServerExplosion.StackCollector(pos, stack));
    }

    private boolean interactsWithBlocks() {
        return this.blockInteraction != Explosion.BlockInteraction.KEEP;
    }

    public Map<Player, Vec3> getHitPlayers() {
        return this.hitPlayers;
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Override
    public @Nullable LivingEntity getIndirectSourceEntity() {
        return Explosion.getIndirectSourceEntity(this.source);
    }

    @Override
    public @Nullable Entity getDirectSourceEntity() {
        return this.source;
    }

    public DamageSource getDamageSource() {
        return this.damageSource;
    }

    @Override
    public Explosion.BlockInteraction getBlockInteraction() {
        return this.blockInteraction;
    }

    @Override
    public boolean canTriggerBlocks() {
        return this.blockInteraction == Explosion.BlockInteraction.TRIGGER_BLOCK
            && (this.source == null || this.source.getType() != EntityType.BREEZE_WIND_CHARGE || this.level.getGameRules().get(GameRules.MOB_GRIEFING));
    }

    @Override
    public boolean shouldAffectBlocklikeEntities() {
        boolean flag = this.level.getGameRules().get(GameRules.MOB_GRIEFING);
        boolean flag1 = this.source == null || this.source.getType() != EntityType.BREEZE_WIND_CHARGE && this.source.getType() != EntityType.WIND_CHARGE;
        return flag ? flag1 : this.blockInteraction.shouldAffectBlocklikeEntities() && flag1;
    }

    public boolean isSmall() {
        return this.radius < 2.0F || !this.interactsWithBlocks();
    }

    static class StackCollector {
        final BlockPos pos;
        ItemStack stack;

        StackCollector(BlockPos pos, ItemStack stack) {
            this.pos = pos;
            this.stack = stack;
        }

        public void tryMerge(ItemStack stack) {
            if (ItemEntity.areMergable(this.stack, stack)) {
                this.stack = ItemEntity.merge(this.stack, stack, 16);
            }
        }
    }
}
