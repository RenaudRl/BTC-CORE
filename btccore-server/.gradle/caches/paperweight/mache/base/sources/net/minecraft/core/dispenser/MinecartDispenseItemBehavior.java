package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

public class MinecartDispenseItemBehavior extends DefaultDispenseItemBehavior {
    private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();
    private final EntityType<? extends AbstractMinecart> entityType;

    public MinecartDispenseItemBehavior(EntityType<? extends AbstractMinecart> entityType) {
        this.entityType = entityType;
    }

    @Override
    public ItemStack execute(BlockSource blockSource, ItemStack item) {
        Direction direction = blockSource.state().getValue(DispenserBlock.FACING);
        ServerLevel serverLevel = blockSource.level();
        Vec3 vec3 = blockSource.center();
        double d = vec3.x() + direction.getStepX() * 1.125;
        double d1 = Math.floor(vec3.y()) + direction.getStepY();
        double d2 = vec3.z() + direction.getStepZ() * 1.125;
        BlockPos blockPos = blockSource.pos().relative(direction);
        BlockState blockState = serverLevel.getBlockState(blockPos);
        double d3;
        if (blockState.is(BlockTags.RAILS)) {
            if (getRailShape(blockState).isSlope()) {
                d3 = 0.6;
            } else {
                d3 = 0.1;
            }
        } else {
            if (!blockState.isAir()) {
                return this.defaultDispenseItemBehavior.dispense(blockSource, item);
            }

            BlockState blockState1 = serverLevel.getBlockState(blockPos.below());
            if (!blockState1.is(BlockTags.RAILS)) {
                return this.defaultDispenseItemBehavior.dispense(blockSource, item);
            }

            if (direction != Direction.DOWN && getRailShape(blockState1).isSlope()) {
                d3 = -0.4;
            } else {
                d3 = -0.9;
            }
        }

        Vec3 vec31 = new Vec3(d, d1 + d3, d2);
        AbstractMinecart abstractMinecart = AbstractMinecart.createMinecart(
            serverLevel, vec31.x, vec31.y, vec31.z, this.entityType, EntitySpawnReason.DISPENSER, item, null
        );
        if (abstractMinecart != null) {
            serverLevel.addFreshEntity(abstractMinecart);
            item.shrink(1);
        }

        return item;
    }

    private static RailShape getRailShape(BlockState state) {
        return state.getBlock() instanceof BaseRailBlock baseRailBlock ? state.getValue(baseRailBlock.getShapeProperty()) : RailShape.NORTH_SOUTH;
    }

    @Override
    protected void playSound(BlockSource blockSource) {
        blockSource.level().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, blockSource.pos(), 0);
    }
}
