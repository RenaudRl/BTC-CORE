package net.minecraft.world.entity.ai.behavior;

import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

public class ResetProfession {
    public static BehaviorControl<Villager> create() {
        return BehaviorBuilder.create(
            instance -> instance.group(instance.absent(MemoryModuleType.JOB_SITE)).apply(instance, jobSite -> (level, villager, gameTime) -> {
                VillagerData villagerData = villager.getVillagerData();
                boolean flag = !villagerData.profession().is(VillagerProfession.NONE) && !villagerData.profession().is(VillagerProfession.NITWIT);
                if (flag && villager.getVillagerXp() == 0 && villagerData.level() <= 1) {
                    villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NONE));
                    villager.refreshBrain(level);
                    return true;
                } else {
                    return false;
                }
            })
        );
    }
}
