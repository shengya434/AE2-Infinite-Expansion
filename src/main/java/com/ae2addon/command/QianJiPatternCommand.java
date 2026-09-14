package com.ae2addon.command;

import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import com.ae2addon.recipe.QianJiRecipeModel;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 千机自有样板相关指令（2026-09-15）：
 * <pre>
 * /qianji list [过滤词]        列举当前世界的可处理配方（id + 主产物 + 概率产出条数）
 * /qianji make &lt;配方id&gt;       按配方 id 生成一张千机样板（用于测试；正式入口是 JEI 页）
 * </pre>
 */
public class QianJiPatternCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("qianji")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, ""))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "filter")))))
                .then(Commands.literal("make")
                        .then(Commands.argument("recipe", StringArgumentType.greedyString())
                                .executes(QianJiPatternCommand::make)))
        );
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String filter) {
        var level = ctx.getSource().getLevel();
        int shown = 0;
        int total = 0;
        for (var recipe : QianJiRecipeModel.candidates(level)) {
            ResourceLocation id = recipe.getId();
            if (id == null) continue;
            if (!filter.isEmpty() && !id.toString().contains(filter)) continue;
            QianJiPatternData data = QianJiRecipeModel.fromRecipe(recipe, level);
            if (data == null) continue;
            total++;
            if (shown < 30) {
                shown++;
                final String line = "§7" + id + " §8→ §a"
                        + (data.primary().isEmpty() ? "无主产物"
                        : data.primary().get(0).item().getDescription().getString())
                        + (data.chanced().isEmpty() ? ""
                        : " §d(+" + data.chanced().size() + " 概率产出)");
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        }
        final int counted = total;
        final int displayed = shown;
        ctx.getSource().sendSuccess(() -> Component.literal("§e共 " + counted + " 条可处理配方"
                + (counted > displayed ? "（只显示前 30 条）" : "")), false);
        return counted;
    }

    private static int make(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "recipe");
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();

        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            source.sendFailure(Component.literal("§c无效的配方 id: " + id));
            return 0;
        }
        var found = level.getRecipeManager().byKey(rl);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("§c找不到配方: " + id));
            return 0;
        }
        QianJiPatternData data = QianJiRecipeModel.fromRecipe(found.get(), level);
        if (data == null) {
            source.sendFailure(Component.literal("§c该配方没提取出可用数据"));
            return 0;
        }

        ItemStack stack = new ItemStack(ModItems.QIAN_JI_PATTERN.get());
        data.writeTo(stack);

        var player = source.getPlayer();
        if (player == null || !player.getInventory().add(stack)) {
            if (player != null) player.drop(stack, false);
        }
        source.sendSuccess(() -> Component.literal("§a已生成千机样板: §7" + id
                + " §8(主产物 " + data.primary().size() + " / 概率产出 " + data.chanced().size() + ")"), false);
        return 1;
    }
}
