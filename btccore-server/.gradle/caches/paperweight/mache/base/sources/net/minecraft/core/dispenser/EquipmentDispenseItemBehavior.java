package net.minecraft.core.dispenser;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;

public class EquipmentDispenseItemBehavior extends DefaultDispenseItemBehavior {
    public static final EquipmentDispenseItemBehavior INSTANCE = new EquipmentDispenseItemBehavior();

    @Override
    protected ItemStack execute(BlockSource blockSource, ItemStack item) {
        return dispenseEquipment(blockSource, item) ? item : super.execute(blockSource, item);
    }

    public static boolean dispenseEquipment(BlockSource blockSource, ItemStack item) {
        BlockPos blockPos = blockSource.pos().relative(blockSource.state().getValue(DispenserBlock.FACING));
        List<LivingEntity> entitiesOfClass = blockSource.level()
            .getEntitiesOfClass(LivingEntity.class, new AABB(blockPos), entity -> entity.canEquipWithDispenser(item));
        if (entitiesOfClass.isEmpty()) {
            return false;
        } else {
            LivingEntity livingEntity = entitiesOfClass.getFirst();
            EquipmentSlot equipmentSlotForItem = livingEntity.getEquipmentSlotForItem(item);
            ItemStack itemStack = item.split(1);
            livingEntity.setItemSlot(equipmentSlotForItem, itemStack);
            if (livingEntity instanceof Mob mob) {
                mob.setGuaranteedDrop(equipmentSlotForItem);
                mob.setPersistenceRequired();
            }

            return true;
        }
    }
}
