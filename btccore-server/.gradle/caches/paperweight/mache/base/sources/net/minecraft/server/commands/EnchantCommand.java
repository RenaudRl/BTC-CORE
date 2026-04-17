package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public class EnchantCommand {
    private static final DynamicCommandExceptionType ERROR_NOT_LIVING_ENTITY = new DynamicCommandExceptionType(
        entity -> Component.translatableEscape("commands.enchant.failed.entity", entity)
    );
    private static final DynamicCommandExceptionType ERROR_NO_ITEM = new DynamicCommandExceptionType(
        entity -> Component.translatableEscape("commands.enchant.failed.itemless", entity)
    );
    private static final DynamicCommandExceptionType ERROR_INCOMPATIBLE = new DynamicCommandExceptionType(
        item -> Component.translatableEscape("commands.enchant.failed.incompatible", item)
    );
    private static final Dynamic2CommandExceptionType ERROR_LEVEL_TOO_HIGH = new Dynamic2CommandExceptionType(
        (requestedLevel, maxLevel) -> Component.translatableEscape("commands.enchant.failed.level", requestedLevel, maxLevel)
    );
    private static final SimpleCommandExceptionType ERROR_NOTHING_HAPPENED = new SimpleCommandExceptionType(Component.translatable("commands.enchant.failed"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("enchant")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("targets", EntityArgument.entities())
                        .then(
                            Commands.argument("enchantment", ResourceArgument.resource(context, Registries.ENCHANTMENT))
                                .executes(
                                    commandContext -> enchant(
                                        commandContext.getSource(),
                                        EntityArgument.getEntities(commandContext, "targets"),
                                        ResourceArgument.getEnchantment(commandContext, "enchantment"),
                                        1
                                    )
                                )
                                .then(
                                    Commands.argument("level", IntegerArgumentType.integer(0))
                                        .executes(
                                            context1 -> enchant(
                                                context1.getSource(),
                                                EntityArgument.getEntities(context1, "targets"),
                                                ResourceArgument.getEnchantment(context1, "enchantment"),
                                                IntegerArgumentType.getInteger(context1, "level")
                                            )
                                        )
                                )
                        )
                )
        );
    }

    private static int enchant(CommandSourceStack source, Collection<? extends Entity> targets, Holder<Enchantment> enchantment, int level) throws CommandSyntaxException {
        Enchantment enchantment1 = enchantment.value();
        if (level > enchantment1.getMaxLevel()) {
            throw ERROR_LEVEL_TOO_HIGH.create(level, enchantment1.getMaxLevel());
        } else {
            int i = 0;

            for (Entity entity : targets) {
                if (entity instanceof LivingEntity livingEntity) {
                    ItemStack mainHandItem = livingEntity.getMainHandItem();
                    if (!mainHandItem.isEmpty()) {
                        if (enchantment1.canEnchant(mainHandItem)
                            && EnchantmentHelper.isEnchantmentCompatible(EnchantmentHelper.getEnchantmentsForCrafting(mainHandItem).keySet(), enchantment)) {
                            mainHandItem.enchant(enchantment, level);
                            i++;
                        } else if (targets.size() == 1) {
                            throw ERROR_INCOMPATIBLE.create(mainHandItem.getHoverName().getString());
                        }
                    } else if (targets.size() == 1) {
                        throw ERROR_NO_ITEM.create(livingEntity.getName().getString());
                    }
                } else if (targets.size() == 1) {
                    throw ERROR_NOT_LIVING_ENTITY.create(entity.getName().getString());
                }
            }

            if (i == 0) {
                throw ERROR_NOTHING_HAPPENED.create();
            } else {
                if (targets.size() == 1) {
                    source.sendSuccess(
                        () -> Component.translatable(
                            "commands.enchant.success.single", Enchantment.getFullname(enchantment, level), targets.iterator().next().getDisplayName()
                        ),
                        true
                    );
                } else {
                    source.sendSuccess(
                        () -> Component.translatable("commands.enchant.success.multiple", Enchantment.getFullname(enchantment, level), targets.size()), true
                    );
                }

                return i;
            }
        }
    }
}
