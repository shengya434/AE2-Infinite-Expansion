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
                .then(Commands.literal("probe")
                        .then(Commands.argument("recipe", StringArgumentType.greedyString())
                                .executes(QianJiPatternCommand::probe)))
        );
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * 逐层诊断一条配方的提取情况（GT 反射到底读到什么、我们抽出了什么）——
     * 2026-09-15 加：sensei 反馈「30 条配方没一条能用」，需要一眼看出断在哪一层。
     */
    private static int probe(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "recipe");
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();

        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            source.sendFailure(Component.literal("§c无效 id: " + id));
            return 0;
        }
        var found = level.getRecipeManager().byKey(rl);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("§c配方不存在: " + id));
            return 0;
        }
        var recipe = found.get();

        say(source, "§e═══ 配方诊断: " + id);
        say(source, "§7类名: §f" + recipe.getClass().getName());
        say(source, "§7是 GT 配方: §f" + com.ae2addon.compat.GregTechCompat.isGtRecipe(recipe));

        ItemStack standard = recipe.getResultItem(level.registryAccess());
        say(source, "§7标准 getResultItem: §f" + (standard.isEmpty() ? "（空）"
                : standard.getHoverName().getString() + " ×" + standard.getCount()));
        say(source, "§7标准 getIngredients 条数: §f" + recipe.getIngredients().size());

        var gtSlots = com.ae2addon.compat.GregTechCompat.inputSlots(recipe);
        say(source, "§7GT 输入槽数（含流体）: §f" + gtSlots.size());
        for (var slot : gtSlots) {
            var sb = new StringBuilder();
            for (var opt : slot) {
                if (!sb.isEmpty()) sb.append("§8/");
                sb.append("§8").append(opt.what().getDisplayName().getString()).append(" ×").append(opt.amount());
            }
            say(source, "§8  · [" + sb + "§8]");
        }
        // 逐能力条目直铺类型（流体读不到时看这里）
        for (String line : com.ae2addon.compat.GregTechCompat.describeEntries(recipe, "inputs")) {
            say(source, "§8  · 输入能力条目: " + line);
        }
        for (String line : com.ae2addon.compat.GregTechCompat.describeEntries(recipe, "outputs")) {
            say(source, "§8  · 产出能力条目: " + line);
        }

        var gtOutputs = com.ae2addon.compat.GregTechCompat.outputs(recipe);
        say(source, "§7GT 产出数（物品 + 流体）: §f" + gtOutputs.size());
        for (var c : gtOutputs) {
            say(source, "§8  · " + c.stack().what().getDisplayName().getString() + " ×" + c.stack().amount()
                    + " §8几率=" + (c.chance() >= 0f ? Math.round(c.chance() * 100) + "%" : "未声明"));
        }

        var byproducts = com.ae2addon.util.RecipeByproducts.extract(recipe, level);
        say(source, "§7RecipeByproducts 抽出概率产出: §f" + byproducts.size());
        for (var c : byproducts) {
            say(source, "§8  · " + c.stack().getHoverName().getString()
                    + " §8几率=" + (c.chance() > 0f ? Math.round(c.chance() * 100) + "%" : "未知"));
        }

        QianJiPatternData data = QianJiRecipeModel.fromRecipe(recipe, level);
        if (data == null) {
            say(source, "§c→ 我们提取结果: 失败（无输入且无产出）");
        } else {
            say(source, "§a→ 我们提取结果: 输入槽 " + data.inputs().size()
                    + " / 主产物 " + data.primary().size()
                    + " / 概率产出 " + data.chanced().size());
            for (String line : data.describe()) say(source, "§8  " + line);
        }
        return 1;
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
                        : data.primary().get(0).stack().what().getDisplayName().getString())
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
            source.sendFailure(Component.literal("§c该配方没提取出可用数据（输入/产出都空）"
                    + " §7—— 跑 /qianji probe " + id + " 看断在哪一层"));
            return 0;
        }
        if (data.primary().isEmpty()) {
            source.sendFailure(Component.literal("§c该配方没提取出主产物（几率产出全为概率？）"
                    + " §7—— 跑 /qianji probe " + id + " 看 GT 产出表"));
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
