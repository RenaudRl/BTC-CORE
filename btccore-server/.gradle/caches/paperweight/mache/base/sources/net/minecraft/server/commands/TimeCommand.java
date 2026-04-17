package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public class TimeCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("time")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("set")
                        .then(Commands.literal("day").executes(context -> setTime(context.getSource(), 1000)))
                        .then(Commands.literal("noon").executes(context -> setTime(context.getSource(), 6000)))
                        .then(Commands.literal("night").executes(context -> setTime(context.getSource(), 13000)))
                        .then(Commands.literal("midnight").executes(context -> setTime(context.getSource(), 18000)))
                        .then(
                            Commands.argument("time", TimeArgument.time())
                                .executes(context -> setTime(context.getSource(), IntegerArgumentType.getInteger(context, "time")))
                        )
                )
                .then(
                    Commands.literal("add")
                        .then(
                            Commands.argument("time", TimeArgument.time())
                                .executes(context -> addTime(context.getSource(), IntegerArgumentType.getInteger(context, "time")))
                        )
                )
                .then(
                    Commands.literal("query")
                        .then(Commands.literal("daytime").executes(context -> queryTime(context.getSource(), getDayTime(context.getSource().getLevel()))))
                        .then(
                            Commands.literal("gametime")
                                .executes(context -> queryTime(context.getSource(), (int)(context.getSource().getLevel().getGameTime() % 2147483647L)))
                        )
                        .then(
                            Commands.literal("day")
                                .executes(context -> queryTime(context.getSource(), (int)(context.getSource().getLevel().getDayCount() % 2147483647L)))
                        )
                )
        );
    }

    private static int getDayTime(ServerLevel level) {
        return (int)(level.getDayTime() % 24000L);
    }

    private static int queryTime(CommandSourceStack source, int time) {
        source.sendSuccess(() -> Component.translatable("commands.time.query", time), false);
        return time;
    }

    public static int setTime(CommandSourceStack source, int time) {
        for (ServerLevel serverLevel : source.getServer().getAllLevels()) {
            serverLevel.setDayTime(time);
        }

        source.getServer().forceTimeSynchronization();
        source.sendSuccess(() -> Component.translatable("commands.time.set", time), true);
        return getDayTime(source.getLevel());
    }

    public static int addTime(CommandSourceStack source, int amount) {
        for (ServerLevel serverLevel : source.getServer().getAllLevels()) {
            serverLevel.setDayTime(serverLevel.getDayTime() + amount);
        }

        source.getServer().forceTimeSynchronization();
        int dayTime = getDayTime(source.getLevel());
        source.sendSuccess(() -> Component.translatable("commands.time.set", dayTime), true);
        return dayTime;
    }
}
