package net.minecraft.world;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public interface RandomizableContainer extends Container {
    String LOOT_TABLE_TAG = "LootTable";
    String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    @Nullable ResourceKey<LootTable> getLootTable();

    void setLootTable(@Nullable ResourceKey<LootTable> lootTable);

    default void setLootTable(ResourceKey<LootTable> lootTable, long seed) {
        this.setLootTable(lootTable);
        this.setLootTableSeed(seed);
    }

    long getLootTableSeed();

    void setLootTableSeed(long seed);

    BlockPos getBlockPos();

    @Nullable Level getLevel();

    static void setBlockEntityLootTable(BlockGetter level, RandomSource random, BlockPos pos, ResourceKey<LootTable> lootTable) {
        if (level.getBlockEntity(pos) instanceof RandomizableContainer randomizableContainer) {
            randomizableContainer.setLootTable(lootTable, random.nextLong());
        }
    }

    default boolean tryLoadLootTable(ValueInput input) {
        ResourceKey<LootTable> resourceKey = input.read("LootTable", LootTable.KEY_CODEC).orElse(null);
        this.setLootTable(resourceKey);
        this.setLootTableSeed(input.getLongOr("LootTableSeed", 0L));
        return resourceKey != null;
    }

    default boolean trySaveLootTable(ValueOutput output) {
        ResourceKey<LootTable> lootTable = this.getLootTable();
        if (lootTable == null) {
            return false;
        } else {
            output.store("LootTable", LootTable.KEY_CODEC, lootTable);
            long lootTableSeed = this.getLootTableSeed();
            if (lootTableSeed != 0L) {
                output.putLong("LootTableSeed", lootTableSeed);
            }

            return true;
        }
    }

    default void unpackLootTable(@Nullable Player player) {
        Level level = this.getLevel();
        BlockPos blockPos = this.getBlockPos();
        ResourceKey<LootTable> lootTable = this.getLootTable();
        if (lootTable != null && level != null && level.getServer() != null) {
            LootTable lootTable1 = level.getServer().reloadableRegistries().getLootTable(lootTable);
            if (player instanceof ServerPlayer) {
                CriteriaTriggers.GENERATE_LOOT.trigger((ServerPlayer)player, lootTable);
            }

            this.setLootTable(null);
            LootParams.Builder builder = new LootParams.Builder((ServerLevel)level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(blockPos));
            if (player != null) {
                builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
            }

            lootTable1.fill(this, builder.create(LootContextParamSets.CHEST), this.getLootTableSeed());
        }
    }
}
