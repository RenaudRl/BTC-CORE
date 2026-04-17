package net.minecraft.world.entity.ai.behavior;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

public class StartAttacking {
    public static <E extends Mob> BehaviorControl<E> create(StartAttacking.TargetFinder<E> targetFinder) {
        return create((level, mob) -> true, targetFinder);
    }

    public static <E extends Mob> BehaviorControl<E> create(StartAttacking.StartAttackingCondition<E> condition, StartAttacking.TargetFinder<E> targetFinder) {
        return BehaviorBuilder.create(
            instance -> instance.group(instance.absent(MemoryModuleType.ATTACK_TARGET), instance.registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE))
                .apply(instance, (memoryAccessor, memoryAccessor1) -> (level, entity, gameTime) -> {
                    if (!condition.test(level, entity)) {
                        return false;
                    } else {
                        Optional<? extends LivingEntity> optional = targetFinder.get(level, entity);
                        if (optional.isEmpty()) {
                            return false;
                        } else {
                            LivingEntity livingEntity = optional.get();
                            if (!entity.canAttack(livingEntity)) {
                                return false;
                            } else {
                                memoryAccessor.set(livingEntity);
                                memoryAccessor1.erase();
                                return true;
                            }
                        }
                    }
                })
        );
    }

    @FunctionalInterface
    public interface StartAttackingCondition<E> {
        boolean test(ServerLevel level, E mob);
    }

    @FunctionalInterface
    public interface TargetFinder<E> {
        Optional<? extends LivingEntity> get(ServerLevel level, E mob);
    }
}
