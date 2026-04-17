package net.minecraft.world.entity.player;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import org.jspecify.annotations.Nullable;

public class StackedItemContents {
    private final StackedContents<Holder<Item>> raw = new StackedContents<>();

    public void accountSimpleStack(ItemStack stack) {
        if (Inventory.isUsableForCrafting(stack)) {
            this.accountStack(stack);
        }
    }

    public void accountStack(ItemStack stack) {
        this.accountStack(stack, stack.getMaxStackSize());
    }

    public void accountStack(ItemStack stack, int maxStackSize) {
        if (!stack.isEmpty()) {
            int min = Math.min(maxStackSize, stack.getCount());
            this.raw.account(stack.getItemHolder(), min);
        }
    }

    public boolean canCraft(Recipe<?> recipe, StackedContents.@Nullable Output<Holder<Item>> output) {
        return this.canCraft(recipe, 1, output);
    }

    public boolean canCraft(Recipe<?> recipe, int maxCount, StackedContents.@Nullable Output<Holder<Item>> output) {
        PlacementInfo placementInfo = recipe.placementInfo();
        return !placementInfo.isImpossibleToPlace() && this.canCraft(placementInfo.ingredients(), maxCount, output);
    }

    public boolean canCraft(List<? extends StackedContents.IngredientInfo<Holder<Item>>> ingredients, StackedContents.@Nullable Output<Holder<Item>> output) {
        return this.canCraft(ingredients, 1, output);
    }

    private boolean canCraft(
        List<? extends StackedContents.IngredientInfo<Holder<Item>>> ingredients, int maxCount, StackedContents.@Nullable Output<Holder<Item>> output
    ) {
        return this.raw.tryPick(ingredients, maxCount, output);
    }

    public int getBiggestCraftableStack(Recipe<?> recipe, StackedContents.@Nullable Output<Holder<Item>> output) {
        return this.getBiggestCraftableStack(recipe, Integer.MAX_VALUE, output);
    }

    public int getBiggestCraftableStack(Recipe<?> recipe, int maxCount, StackedContents.@Nullable Output<Holder<Item>> output) {
        return this.raw.tryPickAll(recipe.placementInfo().ingredients(), maxCount, output);
    }

    public void clear() {
        this.raw.clear();
    }
}
