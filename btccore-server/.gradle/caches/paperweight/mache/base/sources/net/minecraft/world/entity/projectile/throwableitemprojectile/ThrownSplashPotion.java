package net.minecraft.world.entity.projectile.throwableitemprojectile;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

public class ThrownSplashPotion extends AbstractThrownPotion {
    public ThrownSplashPotion(EntityType<? extends ThrownSplashPotion> type, Level level) {
        super(type, level);
    }

    public ThrownSplashPotion(Level level, LivingEntity owner, ItemStack item) {
        super(EntityType.SPLASH_POTION, level, owner, item);
    }

    public ThrownSplashPotion(Level level, double x, double y, double z, ItemStack stack) {
        super(EntityType.SPLASH_POTION, level, x, y, z, stack);
    }

    @Override
    public Item getDefaultItem() {
        return Items.SPLASH_POTION;
    }

    @Override
    public void onHitAsPotion(ServerLevel level, ItemStack stack, HitResult hitResult) {
        PotionContents potionContents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        float orDefault = stack.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0F);
        Iterable<MobEffectInstance> allEffects = potionContents.getAllEffects();
        AABB aabb = this.getBoundingBox().move(hitResult.getLocation().subtract(this.position()));
        AABB aabb1 = aabb.inflate(4.0, 2.0, 4.0);
        List<LivingEntity> entitiesOfClass = this.level().getEntitiesOfClass(LivingEntity.class, aabb1);
        float f = ProjectileUtil.computeMargin(this);
        if (!entitiesOfClass.isEmpty()) {
            Entity effectSource = this.getEffectSource();

            for (LivingEntity livingEntity : entitiesOfClass) {
                if (livingEntity.isAffectedByPotions()) {
                    double d = aabb.distanceToSqr(livingEntity.getBoundingBox().inflate(f));
                    if (d < 16.0) {
                        double d1 = 1.0 - Math.sqrt(d) / 4.0;

                        for (MobEffectInstance mobEffectInstance : allEffects) {
                            Holder<MobEffect> effect = mobEffectInstance.getEffect();
                            if (effect.value().isInstantenous()) {
                                effect.value().applyInstantenousEffect(level, this, this.getOwner(), livingEntity, mobEffectInstance.getAmplifier(), d1);
                            } else {
                                int i = mobEffectInstance.mapDuration(i1 -> (int)(d1 * i1 * orDefault + 0.5));
                                MobEffectInstance mobEffectInstance1 = new MobEffectInstance(
                                    effect, i, mobEffectInstance.getAmplifier(), mobEffectInstance.isAmbient(), mobEffectInstance.isVisible()
                                );
                                if (!mobEffectInstance1.endsWithin(20)) {
                                    livingEntity.addEffect(mobEffectInstance1, effectSource);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
