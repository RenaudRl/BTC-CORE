package net.minecraft.world.entity.ai.behavior;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

public class PoiCompetitorScan {
    public static BehaviorControl<Villager> create() {
        return BehaviorBuilder.create(
            instance -> instance.group(instance.present(MemoryModuleType.JOB_SITE), instance.present(MemoryModuleType.NEAREST_LIVING_ENTITIES))
                .apply(
                    instance,
                    (jobSite, nearestLivingEntities) -> (level, villager, gameTime) -> {
                        GlobalPos globalPos = instance.get(jobSite);
                        level.getPoiManager()
                            .getType(globalPos.pos())
                            .ifPresent(
                                poi -> instance.<List<LivingEntity>>get(nearestLivingEntities)
                                    .stream()
                                    .filter(entity -> entity instanceof Villager && entity != villager)
                                    .map(entity -> (Villager)entity)
                                    .filter(LivingEntity::isAlive)
                                    .filter(v -> competesForSameJobsite(globalPos, poi, v))
                                    .reduce(villager, PoiCompetitorScan::selectWinner)
                            );
                        return true;
                    }
                )
        );
    }

    private static Villager selectWinner(Villager villagerA, Villager villagerB) {
        Villager villager;
        Villager villager1;
        if (villagerA.getVillagerXp() > villagerB.getVillagerXp()) {
            villager = villagerA;
            villager1 = villagerB;
        } else {
            villager = villagerB;
            villager1 = villagerA;
        }

        villager1.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        return villager;
    }

    private static boolean competesForSameJobsite(GlobalPos jobSitePos, Holder<PoiType> poiType, Villager villager) {
        Optional<GlobalPos> memory = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        return memory.isPresent() && jobSitePos.equals(memory.get()) && hasMatchingProfession(poiType, villager.getVillagerData().profession());
    }

    private static boolean hasMatchingProfession(Holder<PoiType> poiType, Holder<VillagerProfession> profession) {
        return profession.value().heldJobSite().test(poiType);
    }
}
