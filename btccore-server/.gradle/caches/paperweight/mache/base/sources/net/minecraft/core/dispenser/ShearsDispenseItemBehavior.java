package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;

public class ShearsDispenseItemBehavior extends OptionalDispenseItemBehavior {
    @Override
    protected ItemStack execute(BlockSource blockSource, ItemStack item) {
        ServerLevel serverLevel = blockSource.level();
        if (!serverLevel.isClientSide()) {
            BlockPos blockPos = blockSource.pos().relative(blockSource.state().getValue(DispenserBlock.FACING));
            this.setSuccess(tryShearBeehive(serverLevel, item, blockPos) || tryShearEntity(serverLevel, blockPos, item));
            if (this.isSuccess()) {
                item.hurtAndBreak(1, serverLevel, null, item1 -> {});
            }
        }

        return item;
    }

    private static boolean tryShearBeehive(ServerLevel level, ItemStack stack, BlockPos pos) {
        BlockState blockState = level.getBlockState(pos);
        if (blockState.is(
            BlockTags.BEEHIVES, blockStateBase -> blockStateBase.hasProperty(BeehiveBlock.HONEY_LEVEL) && blockStateBase.getBlock() instanceof BeehiveBlock
        )) {
            int honeyLevelValue = blockState.getValue(BeehiveBlock.HONEY_LEVEL);
            if (honeyLevelValue >= 5) {
                level.playSound(null, pos, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
                BeehiveBlock.dropHoneycomb(level, stack, blockState, level.getBlockEntity(pos), null, pos);
                ((BeehiveBlock)blockState.getBlock())
                    .releaseBeesAndResetHoneyLevel(level, blockState, pos, null, BeehiveBlockEntity.BeeReleaseStatus.BEE_RELEASED);
                level.gameEvent(null, GameEvent.SHEAR, pos);
                return true;
            }
        }

        return false;
    }

    private static boolean tryShearEntity(ServerLevel level, BlockPos pos, ItemStack stack) {
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(pos), EntitySelector.NO_SPECTATORS)) {
            if (entity.shearOffAllLeashConnections(null)) {
                return true;
            }

            if (entity instanceof Shearable shearable && shearable.readyForShearing()) {
                shearable.shear(level, SoundSource.BLOCKS, stack);
                level.gameEvent(null, GameEvent.SHEAR, pos);
                return true;
            }
        }

        return false;
    }
}
