package net.minecraft.world.inventory;

import com.google.common.base.Suppliers;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class AbstractContainerMenu {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int SLOT_CLICKED_OUTSIDE = -999;
    public static final int QUICKCRAFT_TYPE_CHARITABLE = 0;
    public static final int QUICKCRAFT_TYPE_GREEDY = 1;
    public static final int QUICKCRAFT_TYPE_CLONE = 2;
    public static final int QUICKCRAFT_HEADER_START = 0;
    public static final int QUICKCRAFT_HEADER_CONTINUE = 1;
    public static final int QUICKCRAFT_HEADER_END = 2;
    public static final int CARRIED_SLOT_SIZE = Integer.MAX_VALUE;
    public static final int SLOTS_PER_ROW = 9;
    public static final int SLOT_SIZE = 18;
    public NonNullList<ItemStack> lastSlots = NonNullList.create();
    public NonNullList<Slot> slots = NonNullList.create();
    public List<DataSlot> dataSlots = Lists.newArrayList();
    private ItemStack carried = ItemStack.EMPTY;
    public NonNullList<RemoteSlot> remoteSlots = NonNullList.create();
    public IntList remoteDataSlots = new IntArrayList();
    private RemoteSlot remoteCarried = RemoteSlot.PLACEHOLDER;
    private int stateId;
    public final @Nullable MenuType<?> menuType;
    public final int containerId;
    public int quickcraftType = -1;
    public int quickcraftStatus;
    public final Set<Slot> quickcraftSlots = Sets.newHashSet();
    private final List<ContainerListener> containerListeners = Lists.newArrayList();
    private @Nullable ContainerSynchronizer synchronizer;
    private boolean suppressRemoteUpdates;

    protected AbstractContainerMenu(@Nullable MenuType<?> menuType, int containerId) {
        this.menuType = menuType;
        this.containerId = containerId;
    }

    protected void addInventoryHotbarSlots(Container container, int x, int y) {
        for (int i = 0; i < 9; i++) {
            this.addSlot(new Slot(container, i, x + i * 18, y));
        }
    }

    protected void addInventoryExtendedSlots(Container container, int x, int y) {
        for (int i = 0; i < 3; i++) {
            for (int i1 = 0; i1 < 9; i1++) {
                this.addSlot(new Slot(container, i1 + (i + 1) * 9, x + i1 * 18, y + i * 18));
            }
        }
    }

    protected void addStandardInventorySlots(Container container, int x, int y) {
        this.addInventoryExtendedSlots(container, x, y);
        int i = 4;
        int i1 = 58;
        this.addInventoryHotbarSlots(container, x, y + 58);
    }

    protected static boolean stillValid(ContainerLevelAccess access, Player player, Block targetBlock) {
        return access.evaluate((level, pos) -> !level.getBlockState(pos).is(targetBlock) ? false : player.isWithinBlockInteractionRange(pos, 4.0), true);
    }

    public MenuType<?> getType() {
        if (this.menuType == null) {
            throw new UnsupportedOperationException("Unable to construct this menu by type");
        } else {
            return this.menuType;
        }
    }

    protected static void checkContainerSize(Container container, int minSize) {
        int containerSize = container.getContainerSize();
        if (containerSize < minSize) {
            throw new IllegalArgumentException("Container size " + containerSize + " is smaller than expected " + minSize);
        }
    }

    protected static void checkContainerDataCount(ContainerData intArray, int minSize) {
        int count = intArray.getCount();
        if (count < minSize) {
            throw new IllegalArgumentException("Container data count " + count + " is smaller than expected " + minSize);
        }
    }

    public boolean isValidSlotIndex(int slotIndex) {
        return slotIndex == -1 || slotIndex == -999 || slotIndex < this.slots.size();
    }

    protected Slot addSlot(Slot slot) {
        slot.index = this.slots.size();
        this.slots.add(slot);
        this.lastSlots.add(ItemStack.EMPTY);
        this.remoteSlots.add(this.synchronizer != null ? this.synchronizer.createSlot() : RemoteSlot.PLACEHOLDER);
        return slot;
    }

    protected DataSlot addDataSlot(DataSlot slot) {
        this.dataSlots.add(slot);
        this.remoteDataSlots.add(0);
        return slot;
    }

    protected void addDataSlots(ContainerData array) {
        for (int i = 0; i < array.getCount(); i++) {
            this.addDataSlot(DataSlot.forContainer(array, i));
        }
    }

    public void addSlotListener(ContainerListener listener) {
        if (!this.containerListeners.contains(listener)) {
            this.containerListeners.add(listener);
            this.broadcastChanges();
        }
    }

    public void setSynchronizer(ContainerSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
        this.remoteCarried = synchronizer.createSlot();
        this.remoteSlots.replaceAll(remoteSlot -> synchronizer.createSlot());
        this.sendAllDataToRemote();
    }

    public void sendAllDataToRemote() {
        List<ItemStack> list = new ArrayList<>(this.slots.size());
        int i = 0;

        for (int size = this.slots.size(); i < size; i++) {
            ItemStack item = this.slots.get(i).getItem();
            list.add(item.copy());
            this.remoteSlots.get(i).force(item);
        }

        ItemStack carried = this.getCarried();
        this.remoteCarried.force(carried);
        int size = 0;

        for (int size1 = this.dataSlots.size(); size < size1; size++) {
            this.remoteDataSlots.set(size, this.dataSlots.get(size).get());
        }

        if (this.synchronizer != null) {
            this.synchronizer.sendInitialData(this, list, carried.copy(), this.remoteDataSlots.toIntArray());
        }
    }

    public void removeSlotListener(ContainerListener listener) {
        this.containerListeners.remove(listener);
    }

    public NonNullList<ItemStack> getItems() {
        NonNullList<ItemStack> list = NonNullList.create();

        for (Slot slot : this.slots) {
            list.add(slot.getItem());
        }

        return list;
    }

    public void broadcastChanges() {
        for (int i = 0; i < this.slots.size(); i++) {
            ItemStack item = this.slots.get(i).getItem();
            Supplier<ItemStack> supplier = Suppliers.memoize(item::copy);
            this.triggerSlotListeners(i, item, supplier);
            this.synchronizeSlotToRemote(i, item, supplier);
        }

        this.synchronizeCarriedToRemote();

        for (int i = 0; i < this.dataSlots.size(); i++) {
            DataSlot dataSlot = this.dataSlots.get(i);
            int i1 = dataSlot.get();
            if (dataSlot.checkAndClearUpdateFlag()) {
                this.updateDataSlotListeners(i, i1);
            }

            this.synchronizeDataSlotToRemote(i, i1);
        }
    }

    public void broadcastFullState() {
        for (int i = 0; i < this.slots.size(); i++) {
            ItemStack item = this.slots.get(i).getItem();
            this.triggerSlotListeners(i, item, item::copy);
        }

        for (int i = 0; i < this.dataSlots.size(); i++) {
            DataSlot dataSlot = this.dataSlots.get(i);
            if (dataSlot.checkAndClearUpdateFlag()) {
                this.updateDataSlotListeners(i, dataSlot.get());
            }
        }

        this.sendAllDataToRemote();
    }

    private void updateDataSlotListeners(int slotIndex, int value) {
        for (ContainerListener containerListener : this.containerListeners) {
            containerListener.dataChanged(this, slotIndex, value);
        }
    }

    public void triggerSlotListeners(int slotIndex, ItemStack stack, Supplier<ItemStack> supplier) {
        ItemStack itemStack = this.lastSlots.get(slotIndex);
        if (!ItemStack.matches(itemStack, stack)) {
            ItemStack itemStack1 = supplier.get();
            this.lastSlots.set(slotIndex, itemStack1);

            for (ContainerListener containerListener : this.containerListeners) {
                containerListener.slotChanged(this, slotIndex, itemStack1);
            }
        }
    }

    public void synchronizeSlotToRemote(int slotIndex, ItemStack stack, Supplier<ItemStack> supplier) {
        if (!this.suppressRemoteUpdates) {
            RemoteSlot remoteSlot = this.remoteSlots.get(slotIndex);
            if (!remoteSlot.matches(stack)) {
                remoteSlot.force(stack);
                if (this.synchronizer != null) {
                    this.synchronizer.sendSlotChange(this, slotIndex, supplier.get());
                }
            }
        }
    }

    private void synchronizeDataSlotToRemote(int slotIndex, int value) {
        if (!this.suppressRemoteUpdates) {
            int _int = this.remoteDataSlots.getInt(slotIndex);
            if (_int != value) {
                this.remoteDataSlots.set(slotIndex, value);
                if (this.synchronizer != null) {
                    this.synchronizer.sendDataChange(this, slotIndex, value);
                }
            }
        }
    }

    private void synchronizeCarriedToRemote() {
        if (!this.suppressRemoteUpdates) {
            ItemStack carried = this.getCarried();
            if (!this.remoteCarried.matches(carried)) {
                this.remoteCarried.force(carried);
                if (this.synchronizer != null) {
                    this.synchronizer.sendCarriedChange(this, carried.copy());
                }
            }
        }
    }

    public void setRemoteSlot(int slot, ItemStack stack) {
        this.remoteSlots.get(slot).force(stack);
    }

    public void setRemoteSlotUnsafe(int slot, HashedStack stack) {
        if (slot >= 0 && slot < this.remoteSlots.size()) {
            this.remoteSlots.get(slot).receive(stack);
        } else {
            LOGGER.debug("Incorrect slot index: {} available slots: {}", slot, this.remoteSlots.size());
        }
    }

    public void setRemoteCarried(HashedStack stack) {
        this.remoteCarried.receive(stack);
    }

    public boolean clickMenuButton(Player player, int id) {
        return false;
    }

    public Slot getSlot(int slotIndex) {
        return this.slots.get(slotIndex);
    }

    public abstract ItemStack quickMoveStack(Player player, int slotIndex);

    public void setSelectedBundleItemIndex(int slotIndex, int bundleItemIndex) {
        if (slotIndex >= 0 && slotIndex < this.slots.size()) {
            ItemStack item = this.slots.get(slotIndex).getItem();
            BundleItem.toggleSelectedItem(item, bundleItemIndex);
        }
    }

    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        try {
            this.doClick(slotIndex, button, clickType, player);
        } catch (Exception var8) {
            CrashReport crashReport = CrashReport.forThrowable(var8, "Container click");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Click info");
            crashReportCategory.setDetail("Menu Type", () -> this.menuType != null ? BuiltInRegistries.MENU.getKey(this.menuType).toString() : "<no type>");
            crashReportCategory.setDetail("Menu Class", () -> this.getClass().getCanonicalName());
            crashReportCategory.setDetail("Slot Count", this.slots.size());
            crashReportCategory.setDetail("Slot", slotIndex);
            crashReportCategory.setDetail("Button", button);
            crashReportCategory.setDetail("Type", clickType);
            throw new ReportedException(crashReport);
        }
    }

    private void doClick(int slotIndex, int button, ClickType clickType, Player player) {
        Inventory inventory = player.getInventory();
        if (clickType == ClickType.QUICK_CRAFT) {
            int i = this.quickcraftStatus;
            this.quickcraftStatus = getQuickcraftHeader(button);
            if ((i != 1 || this.quickcraftStatus != 2) && i != this.quickcraftStatus) {
                this.resetQuickCraft();
            } else if (this.getCarried().isEmpty()) {
                this.resetQuickCraft();
            } else if (this.quickcraftStatus == 0) {
                this.quickcraftType = getQuickcraftType(button);
                if (isValidQuickcraftType(this.quickcraftType, player)) {
                    this.quickcraftStatus = 1;
                    this.quickcraftSlots.clear();
                } else {
                    this.resetQuickCraft();
                }
            } else if (this.quickcraftStatus == 1) {
                Slot slot = this.slots.get(slotIndex);
                ItemStack carried = this.getCarried();
                if (canItemQuickReplace(slot, carried, true)
                    && slot.mayPlace(carried)
                    && (this.quickcraftType == 2 || carried.getCount() > this.quickcraftSlots.size())
                    && this.canDragTo(slot)) {
                    this.quickcraftSlots.add(slot);
                }
            } else if (this.quickcraftStatus == 2) {
                if (!this.quickcraftSlots.isEmpty()) {
                    if (this.quickcraftSlots.size() == 1) {
                        int i1 = this.quickcraftSlots.iterator().next().index;
                        this.resetQuickCraft();
                        this.doClick(i1, this.quickcraftType, ClickType.PICKUP, player);
                        return;
                    }

                    ItemStack itemStack = this.getCarried().copy();
                    if (itemStack.isEmpty()) {
                        this.resetQuickCraft();
                        return;
                    }

                    int count = this.getCarried().getCount();

                    for (Slot slot1 : this.quickcraftSlots) {
                        ItemStack carried1 = this.getCarried();
                        if (slot1 != null
                            && canItemQuickReplace(slot1, carried1, true)
                            && slot1.mayPlace(carried1)
                            && (this.quickcraftType == 2 || carried1.getCount() >= this.quickcraftSlots.size())
                            && this.canDragTo(slot1)) {
                            int i2 = slot1.hasItem() ? slot1.getItem().getCount() : 0;
                            int min = Math.min(itemStack.getMaxStackSize(), slot1.getMaxStackSize(itemStack));
                            int min1 = Math.min(getQuickCraftPlaceCount(this.quickcraftSlots, this.quickcraftType, itemStack) + i2, min);
                            count -= min1 - i2;
                            slot1.setByPlayer(itemStack.copyWithCount(min1));
                        }
                    }

                    itemStack.setCount(count);
                    this.setCarried(itemStack);
                }

                this.resetQuickCraft();
            } else {
                this.resetQuickCraft();
            }
        } else if (this.quickcraftStatus != 0) {
            this.resetQuickCraft();
        } else if ((clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE) && (button == 0 || button == 1)) {
            ClickAction clickAction = button == 0 ? ClickAction.PRIMARY : ClickAction.SECONDARY;
            if (slotIndex == SLOT_CLICKED_OUTSIDE) {
                if (!this.getCarried().isEmpty()) {
                    if (clickAction == ClickAction.PRIMARY) {
                        player.drop(this.getCarried(), true);
                        this.setCarried(ItemStack.EMPTY);
                    } else {
                        player.drop(this.getCarried().split(1), true);
                    }
                }
            } else if (clickType == ClickType.QUICK_MOVE) {
                if (slotIndex < 0) {
                    return;
                }

                Slot slot = this.slots.get(slotIndex);
                if (!slot.mayPickup(player)) {
                    return;
                }

                ItemStack carried = this.quickMoveStack(player, slotIndex);

                while (!carried.isEmpty() && ItemStack.isSameItem(slot.getItem(), carried)) {
                    carried = this.quickMoveStack(player, slotIndex);
                }
            } else {
                if (slotIndex < 0) {
                    return;
                }

                Slot slot = this.slots.get(slotIndex);
                ItemStack carried = slot.getItem();
                ItemStack carried2 = this.getCarried();
                player.updateTutorialInventoryAction(carried2, slot.getItem(), clickAction);
                if (!this.tryItemClickBehaviourOverride(player, clickAction, slot, carried, carried2)) {
                    if (carried.isEmpty()) {
                        if (!carried2.isEmpty()) {
                            int i3 = clickAction == ClickAction.PRIMARY ? carried2.getCount() : 1;
                            this.setCarried(slot.safeInsert(carried2, i3));
                        }
                    } else if (slot.mayPickup(player)) {
                        if (carried2.isEmpty()) {
                            int i3 = clickAction == ClickAction.PRIMARY ? carried.getCount() : (carried.getCount() + 1) / 2;
                            Optional<ItemStack> optional = slot.tryRemove(i3, Integer.MAX_VALUE, player);
                            optional.ifPresent(itemStack2 -> {
                                this.setCarried(itemStack2);
                                slot.onTake(player, itemStack2);
                            });
                        } else if (slot.mayPlace(carried2)) {
                            if (ItemStack.isSameItemSameComponents(carried, carried2)) {
                                int i3 = clickAction == ClickAction.PRIMARY ? carried2.getCount() : 1;
                                this.setCarried(slot.safeInsert(carried2, i3));
                            } else if (carried2.getCount() <= slot.getMaxStackSize(carried2)) {
                                this.setCarried(carried);
                                slot.setByPlayer(carried2);
                            }
                        } else if (ItemStack.isSameItemSameComponents(carried, carried2)) {
                            Optional<ItemStack> optional1 = slot.tryRemove(carried.getCount(), carried2.getMaxStackSize() - carried2.getCount(), player);
                            optional1.ifPresent(itemStack2 -> {
                                carried2.grow(itemStack2.getCount());
                                slot.onTake(player, itemStack2);
                            });
                        }
                    }
                }

                slot.setChanged();
            }
        } else if (clickType == ClickType.SWAP && (button >= 0 && button < 9 || button == 40)) {
            ItemStack item = inventory.getItem(button);
            Slot slot = this.slots.get(slotIndex);
            ItemStack carried = slot.getItem();
            if (!item.isEmpty() || !carried.isEmpty()) {
                if (item.isEmpty()) {
                    if (slot.mayPickup(player)) {
                        inventory.setItem(button, carried);
                        slot.onSwapCraft(carried.getCount());
                        slot.setByPlayer(ItemStack.EMPTY);
                        slot.onTake(player, carried);
                    }
                } else if (carried.isEmpty()) {
                    if (slot.mayPlace(item)) {
                        int maxStackSize = slot.getMaxStackSize(item);
                        if (item.getCount() > maxStackSize) {
                            slot.setByPlayer(item.split(maxStackSize));
                        } else {
                            inventory.setItem(button, ItemStack.EMPTY);
                            slot.setByPlayer(item);
                        }
                    }
                } else if (slot.mayPickup(player) && slot.mayPlace(item)) {
                    int maxStackSize = slot.getMaxStackSize(item);
                    if (item.getCount() > maxStackSize) {
                        slot.setByPlayer(item.split(maxStackSize));
                        slot.onTake(player, carried);
                        if (!inventory.add(carried)) {
                            player.drop(carried, true);
                        }
                    } else {
                        inventory.setItem(button, carried);
                        slot.setByPlayer(item);
                        slot.onTake(player, carried);
                    }
                }
            }
        } else if (clickType == ClickType.CLONE && player.hasInfiniteMaterials() && this.getCarried().isEmpty() && slotIndex >= 0) {
            Slot slot2 = this.slots.get(slotIndex);
            if (slot2.hasItem()) {
                ItemStack itemStack = slot2.getItem();
                this.setCarried(itemStack.copyWithCount(itemStack.getMaxStackSize()));
            }
        } else if (clickType == ClickType.THROW && this.getCarried().isEmpty() && slotIndex >= 0) {
            Slot slot2 = this.slots.get(slotIndex);
            int i1 = button == 0 ? 1 : slot2.getItem().getCount();
            if (!player.canDropItems()) {
                return;
            }

            ItemStack carried = slot2.safeTake(i1, Integer.MAX_VALUE, player);
            player.drop(carried, true);
            player.handleCreativeModeItemDrop(carried);
            if (button == 1) {
                while (!carried.isEmpty() && ItemStack.isSameItem(slot2.getItem(), carried)) {
                    if (!player.canDropItems()) {
                        return;
                    }

                    carried = slot2.safeTake(i1, Integer.MAX_VALUE, player);
                    player.drop(carried, true);
                    player.handleCreativeModeItemDrop(carried);
                }
            }
        } else if (clickType == ClickType.PICKUP_ALL && slotIndex >= 0) {
            Slot slot2x = this.slots.get(slotIndex);
            ItemStack itemStack = this.getCarried();
            if (!itemStack.isEmpty() && (!slot2x.hasItem() || !slot2x.mayPickup(player))) {
                int count = button == 0 ? 0 : this.slots.size() - 1;
                int maxStackSize = button == 0 ? 1 : -1;

                for (int i3 = 0; i3 < 2; i3++) {
                    for (int i4 = count; i4 >= 0 && i4 < this.slots.size() && itemStack.getCount() < itemStack.getMaxStackSize(); i4 += maxStackSize) {
                        Slot slot3 = this.slots.get(i4);
                        if (slot3.hasItem()
                            && canItemQuickReplace(slot3, itemStack, true)
                            && slot3.mayPickup(player)
                            && this.canTakeItemForPickAll(itemStack, slot3)) {
                            ItemStack item1 = slot3.getItem();
                            if (i3 != 0 || item1.getCount() != item1.getMaxStackSize()) {
                                ItemStack itemStack1 = slot3.safeTake(item1.getCount(), itemStack.getMaxStackSize() - itemStack.getCount(), player);
                                itemStack.grow(itemStack1.getCount());
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean tryItemClickBehaviourOverride(Player player, ClickAction action, Slot slot, ItemStack clickedItem, ItemStack carriedItem) {
        FeatureFlagSet featureFlagSet = player.level().enabledFeatures();
        return carriedItem.isItemEnabled(featureFlagSet) && carriedItem.overrideStackedOnOther(slot, action, player)
            || clickedItem.isItemEnabled(featureFlagSet)
                && clickedItem.overrideOtherStackedOnMe(carriedItem, slot, action, player, this.createCarriedSlotAccess());
    }

    private SlotAccess createCarriedSlotAccess() {
        return new SlotAccess() {
            @Override
            public ItemStack get() {
                return AbstractContainerMenu.this.getCarried();
            }

            @Override
            public boolean set(ItemStack carried) {
                AbstractContainerMenu.this.setCarried(carried);
                return true;
            }
        };
    }

    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return true;
    }

    public void removed(Player player) {
        if (player instanceof ServerPlayer) {
            ItemStack carried = this.getCarried();
            if (!carried.isEmpty()) {
                dropOrPlaceInInventory(player, carried);
                this.setCarried(ItemStack.EMPTY);
            }
        }
    }

    private static void dropOrPlaceInInventory(Player player, ItemStack stack) {
        boolean flag = player.isRemoved() && player.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION;
        boolean flag1 = player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected();
        if (flag || flag1) {
            player.drop(stack, false);
        } else if (player instanceof ServerPlayer) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    protected void clearContainer(Player player, Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            dropOrPlaceInInventory(player, container.removeItemNoUpdate(i));
        }
    }

    public void slotsChanged(Container container) {
        this.broadcastChanges();
    }

    public void setItem(int slotIndex, int stateId, ItemStack stack) {
        this.getSlot(slotIndex).set(stack);
        this.stateId = stateId;
    }

    public void initializeContents(int stateId, List<ItemStack> items, ItemStack carried) {
        for (int i = 0; i < items.size(); i++) {
            this.getSlot(i).set(items.get(i));
        }

        this.carried = carried;
        this.stateId = stateId;
    }

    public void setData(int id, int data) {
        this.dataSlots.get(id).set(data);
    }

    public abstract boolean stillValid(Player player);

    protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        boolean flag = false;
        int i = startIndex;
        if (reverseDirection) {
            i = endIndex - 1;
        }

        if (stack.isStackable()) {
            while (!stack.isEmpty() && (reverseDirection ? i >= startIndex : i < endIndex)) {
                Slot slot = this.slots.get(i);
                ItemStack item = slot.getItem();
                if (!item.isEmpty() && ItemStack.isSameItemSameComponents(stack, item)) {
                    int i1 = item.getCount() + stack.getCount();
                    int maxStackSize = slot.getMaxStackSize(item);
                    if (i1 <= maxStackSize) {
                        stack.setCount(0);
                        item.setCount(i1);
                        slot.setChanged();
                        flag = true;
                    } else if (item.getCount() < maxStackSize) {
                        stack.shrink(maxStackSize - item.getCount());
                        item.setCount(maxStackSize);
                        slot.setChanged();
                        flag = true;
                    }
                }

                if (reverseDirection) {
                    i--;
                } else {
                    i++;
                }
            }
        }

        if (!stack.isEmpty()) {
            if (reverseDirection) {
                i = endIndex - 1;
            } else {
                i = startIndex;
            }

            while (reverseDirection ? i >= startIndex : i < endIndex) {
                Slot slotx = this.slots.get(i);
                ItemStack itemx = slotx.getItem();
                if (itemx.isEmpty() && slotx.mayPlace(stack)) {
                    int i1 = slotx.getMaxStackSize(stack);
                    slotx.setByPlayer(stack.split(Math.min(stack.getCount(), i1)));
                    slotx.setChanged();
                    flag = true;
                    break;
                }

                if (reverseDirection) {
                    i--;
                } else {
                    i++;
                }
            }
        }

        return flag;
    }

    public static int getQuickcraftType(int eventButton) {
        return eventButton >> 2 & 3;
    }

    public static int getQuickcraftHeader(int clickedButton) {
        return clickedButton & 3;
    }

    public static int getQuickcraftMask(int quickCraftingHeader, int quickCraftingType) {
        return quickCraftingHeader & 3 | (quickCraftingType & 3) << 2;
    }

    public static boolean isValidQuickcraftType(int dragMode, Player player) {
        return dragMode == 0 || dragMode == 1 || dragMode == 2 && player.hasInfiniteMaterials();
    }

    public void resetQuickCraft() {
        this.quickcraftStatus = 0;
        this.quickcraftSlots.clear();
    }

    public static boolean canItemQuickReplace(@Nullable Slot slot, ItemStack stack, boolean stackSizeMatters) {
        boolean flag = slot == null || !slot.hasItem();
        return !flag && ItemStack.isSameItemSameComponents(stack, slot.getItem())
            ? slot.getItem().getCount() + (stackSizeMatters ? 0 : stack.getCount()) <= stack.getMaxStackSize()
            : flag;
    }

    public static int getQuickCraftPlaceCount(Set<Slot> slots, int type, ItemStack stack) {
        return switch (type) {
            case 0 -> Mth.floor((float)stack.getCount() / slots.size());
            case 1 -> 1;
            case 2 -> stack.getMaxStackSize();
            default -> stack.getCount();
        };
    }

    public boolean canDragTo(Slot slot) {
        return true;
    }

    public static int getRedstoneSignalFromBlockEntity(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof Container ? getRedstoneSignalFromContainer((Container)blockEntity) : 0;
    }

    public static int getRedstoneSignalFromContainer(@Nullable Container container) {
        if (container == null) {
            return 0;
        } else {
            float f = 0.0F;

            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack item = container.getItem(i);
                if (!item.isEmpty()) {
                    f += (float)item.getCount() / container.getMaxStackSize(item);
                }
            }

            f /= container.getContainerSize();
            return Mth.lerpDiscrete(f, 0, 15);
        }
    }

    public void setCarried(ItemStack stack) {
        this.carried = stack;
    }

    public ItemStack getCarried() {
        return this.carried;
    }

    public void suppressRemoteUpdates() {
        this.suppressRemoteUpdates = true;
    }

    public void resumeRemoteUpdates() {
        this.suppressRemoteUpdates = false;
    }

    public void transferState(AbstractContainerMenu menu) {
        Table<Container, Integer, Integer> table = HashBasedTable.create();

        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            table.put(slot.container, slot.getContainerSlot(), i);
        }

        for (int i = 0; i < this.slots.size(); i++) {
            Slot slot = this.slots.get(i);
            Integer integer = table.get(slot.container, slot.getContainerSlot());
            if (integer != null) {
                this.lastSlots.set(i, menu.lastSlots.get(integer));
                RemoteSlot remoteSlot = menu.remoteSlots.get(integer);
                RemoteSlot remoteSlot1 = this.remoteSlots.get(i);
                if (remoteSlot instanceof RemoteSlot.Synchronized _synchronized && remoteSlot1 instanceof RemoteSlot.Synchronized _synchronized1) {
                    _synchronized1.copyFrom(_synchronized);
                }
            }
        }
    }

    public OptionalInt findSlot(Container container, int slotIndex) {
        for (int i = 0; i < this.slots.size(); i++) {
            Slot slot = this.slots.get(i);
            if (slot.container == container && slotIndex == slot.getContainerSlot()) {
                return OptionalInt.of(i);
            }
        }

        return OptionalInt.empty();
    }

    public int getStateId() {
        return this.stateId;
    }

    public int incrementStateId() {
        this.stateId = this.stateId + 1 & 32767;
        return this.stateId;
    }
}
