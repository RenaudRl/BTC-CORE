package net.minecraft.world;

import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class CompoundContainer implements Container {
    public final Container container1;
    public final Container container2;

    public CompoundContainer(Container container1, Container container2) {
        this.container1 = container1;
        this.container2 = container2;
    }

    @Override
    public int getContainerSize() {
        return this.container1.getContainerSize() + this.container2.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return this.container1.isEmpty() && this.container2.isEmpty();
    }

    public boolean contains(Container inventory) {
        return this.container1 == inventory || this.container2 == inventory;
    }

    @Override
    public ItemStack getItem(int index) {
        return index >= this.container1.getContainerSize()
            ? this.container2.getItem(index - this.container1.getContainerSize())
            : this.container1.getItem(index);
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        return index >= this.container1.getContainerSize()
            ? this.container2.removeItem(index - this.container1.getContainerSize(), count)
            : this.container1.removeItem(index, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return index >= this.container1.getContainerSize()
            ? this.container2.removeItemNoUpdate(index - this.container1.getContainerSize())
            : this.container1.removeItemNoUpdate(index);
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index >= this.container1.getContainerSize()) {
            this.container2.setItem(index - this.container1.getContainerSize(), stack);
        } else {
            this.container1.setItem(index, stack);
        }
    }

    @Override
    public int getMaxStackSize() {
        return this.container1.getMaxStackSize();
    }

    @Override
    public void setChanged() {
        this.container1.setChanged();
        this.container2.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container1.stillValid(player) && this.container2.stillValid(player);
    }

    @Override
    public void startOpen(ContainerUser user) {
        this.container1.startOpen(user);
        this.container2.startOpen(user);
    }

    @Override
    public void stopOpen(ContainerUser user) {
        this.container1.stopOpen(user);
        this.container2.stopOpen(user);
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return index >= this.container1.getContainerSize()
            ? this.container2.canPlaceItem(index - this.container1.getContainerSize(), stack)
            : this.container1.canPlaceItem(index, stack);
    }

    @Override
    public void clearContent() {
        this.container1.clearContent();
        this.container2.clearContent();
    }
}
