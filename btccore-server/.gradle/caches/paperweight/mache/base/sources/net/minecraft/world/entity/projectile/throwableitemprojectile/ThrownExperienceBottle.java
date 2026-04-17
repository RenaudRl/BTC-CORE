package net.minecraft.world.entity.projectile.throwableitemprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ThrownExperienceBottle extends ThrowableItemProjectile {
    public ThrownExperienceBottle(EntityType<? extends ThrownExperienceBottle> type, Level level) {
        super(type, level);
    }

    public ThrownExperienceBottle(Level level, LivingEntity owner, ItemStack item) {
        super(EntityType.EXPERIENCE_BOTTLE, owner, level, item);
    }

    public ThrownExperienceBottle(Level level, double x, double y, double z, ItemStack item) {
        super(EntityType.EXPERIENCE_BOTTLE, x, y, z, level, item);
    }

    @Override
    public Item getDefaultItem() {
        return Items.EXPERIENCE_BOTTLE;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.07;
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.levelEvent(LevelEvent.PARTICLES_SPELL_POTION_SPLASH, this.blockPosition(), -13083194);
            int i = 3 + serverLevel.random.nextInt(5) + serverLevel.random.nextInt(5);
            if (result instanceof BlockHitResult blockHitResult) {
                Vec3 unitVec3 = blockHitResult.getDirection().getUnitVec3();
                ExperienceOrb.awardWithDirection(serverLevel, result.getLocation(), unitVec3, i);
            } else {
                ExperienceOrb.awardWithDirection(serverLevel, result.getLocation(), this.getDeltaMovement().scale(-1.0), i);
            }

            this.discard();
        }
    }
}
