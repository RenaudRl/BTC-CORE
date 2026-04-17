package net.minecraft.world.inventory;

import net.minecraft.world.item.ItemStack;

public interface ContainerListener {
    void slotChanged(AbstractContainerMenu containerToSend, int dataSlotIndex, ItemStack stack);

    void dataChanged(AbstractContainerMenu containerMenu, int dataSlotIndex, int value);
}
