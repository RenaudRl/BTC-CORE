package net.minecraft.core.dispenser;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LevelEvent;

public class DefaultDispenseItemBehavior implements DispenseItemBehavior {
    private static final int DEFAULT_ACCURACY = 6;

    @Override
    public final ItemStack dispense(BlockSource blockSource, ItemStack item) {
        ItemStack itemStack = this.execute(blockSource, item);
        this.playSound(blockSource);
        this.playAnimation(blockSource, blockSource.state().getValue(DispenserBlock.FACING));
        return itemStack;
    }

    protected ItemStack execute(BlockSource blockSource, ItemStack item) {
        Direction direction = blockSource.state().getValue(DispenserBlock.FACING);
        Position dispensePosition = DispenserBlock.getDispensePosition(blockSource);
        ItemStack itemStack = item.split(1);
        spawnItem(blockSource.level(), itemStack, 6, direction, dispensePosition);
        return item;
    }

    public static void spawnItem(Level level, ItemStack stack, int speed, Direction facing, Position position) {
        double d = position.x();
        double d1 = position.y();
        double d2 = position.z();
        if (facing.getAxis() == Direction.Axis.Y) {
            d1 -= 0.125;
        } else {
            d1 -= 0.15625;
        }

        ItemEntity itemEntity = new ItemEntity(level, d, d1, d2, stack);
        double d3 = level.random.nextDouble() * 0.1 + 0.2;
        itemEntity.setDeltaMovement(
            level.random.triangle(facing.getStepX() * d3, 0.0172275 * speed),
            level.random.triangle(0.2, 0.0172275 * speed),
            level.random.triangle(facing.getStepZ() * d3, 0.0172275 * speed)
        );
        level.addFreshEntity(itemEntity);
    }

    protected void playSound(BlockSource blockSource) {
        playDefaultSound(blockSource);
    }

    protected void playAnimation(BlockSource blockSource, Direction direction) {
        playDefaultAnimation(blockSource, direction);
    }

    private static void playDefaultSound(BlockSource blockSource) {
        blockSource.level().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, blockSource.pos(), 0);
    }

    private static void playDefaultAnimation(BlockSource blockSource, Direction direction) {
        blockSource.level().levelEvent(LevelEvent.PARTICLES_SHOOT_SMOKE, blockSource.pos(), direction.get3DDataValue());
    }

    protected ItemStack consumeWithRemainder(BlockSource blockSource, ItemStack stack, ItemStack remainder) {
        stack.shrink(1);
        if (stack.isEmpty()) {
            return remainder;
        } else {
            this.addToInventoryOrDispense(blockSource, remainder);
            return stack;
        }
    }

    private void addToInventoryOrDispense(BlockSource blockSource, ItemStack remainder) {
        ItemStack itemStack = blockSource.blockEntity().insertItem(remainder);
        if (!itemStack.isEmpty()) {
            Direction direction = blockSource.state().getValue(DispenserBlock.FACING);
            spawnItem(blockSource.level(), itemStack, 6, direction, DispenserBlock.getDispensePosition(blockSource));
            playDefaultSound(blockSource);
            playDefaultAnimation(blockSource, direction);
        }
    }
}
