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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
                .then(Commands.literal("scan")
                        .then(Commands.argument("modid", StringArgumentType.word())
                                .executes(QianJiPatternCommand::scan)))
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

    /** 小按钮：往剪贴板塞一段文本（2026-09-17 sensei：要能把失败配方类拉个清单带走） */
    private static Component copyButton(String label, String payload, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, payload))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /**
     * 可点击的配方 id（2026-09-17 sensei：扫描出来的 id 没法复制、手打太麻烦）：
     * <ul>
     *   <li>点 id 本身 → 把 {@code /qianji probe <id>} **填进**聊天输入框（不直接执行，安全）</li>
     *   <li>[复制] → id 进剪贴板</li>
     *   <li>[样板] → 直接跑 {@code /qianji make <id>}，生成千机样板</li>
     * </ul>
     */
    private static Component clickableId(ResourceLocation id) {
        var label = Component.literal("§f" + id)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/qianji probe " + id))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7点击填入 §f/qianji probe " + id))));
        var copy = Component.literal(" §8[复制]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7复制配方 id 到剪贴板"))));
        var make = Component.literal(" §8[样板]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/qianji make " + id))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7直接生成千机样板"))));
        return label.append(copy).append(make);
    }

    /** 记一条失败原因 + 留个样例（每类最多 3 条，方便我离线去 jar 里查这些 id） */
    private static void record(java.util.Map<String, java.util.LinkedHashMap<String, Integer>> reasons,
                               java.util.Map<String, java.util.List<String>> samples,
                               String type, ResourceLocation id, String reason) {
        reasons.computeIfAbsent(type, k -> new java.util.LinkedHashMap<>()).merge(reason, 1, Integer::sum);
        var list = samples.computeIfAbsent(type, k -> new java.util.ArrayList<>());
        if (list.size() < 3) list.add(id + "  ← " + reason);
    }

    /**
     * 一条配方**为什么读不出来**（2026-09-17：sensei 的清单里 crafting 也有失败，得分清是
     * 「产物读不出」还是「原料读不出」还是「产物被误判成概率产出」）。
     */
    private static String failReason(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                     net.minecraft.world.level.Level level) {
        boolean hasIngredient = false;
        for (var ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) { hasIngredient = true; break; }
            }
            if (hasIngredient) break;
        }
        ItemStack out = recipe.getResultItem(level.registryAccess());
        if (out.isEmpty()) return hasIngredient ? "标准 getResultItem 为空" : "原料与产物都读不出";
        if (!hasIngredient) return "标准 getIngredients 全空";
        return "产物被当成概率产出（RecipeByproducts 撞上主产物）";
    }

    /**
     * 扫描某个 mod 的**全部**配方，把千机读不出来的挑出来（2026-09-17 sensei 要的诊断入口）。
     * <p>
     * 和 {@code /qianji list} 的区别：list 只看 {@code candidates()}（有产出的），
     * scan 直接遍历 RecipeManager 全量 —— 这样「连产出都没读出来」的配方才不会被漏掉。
     * <p>
     * 判据（按严重程度）：
     * <ul>
     *   <li><b>提取失败</b>：fromRecipe 返回 null（输入与产出都空）→ 这类连 JEI 页都不会出现</li>
     *   <li><b>无输入</b>：有产出但一个输入槽都没抽到</li>
     *   <li><b>无主产物</b>：只剩概率产出（AE2 样板必须是确定量）</li>
     * </ul>
     * 先按**配方类型**汇总（一眼看出是哪个类没适配、对应哪个 Java 类），再列前 20 条具体 id。
     */
    private static int scan(CommandContext<CommandSourceStack> ctx) {
        String modid = StringArgumentType.getString(ctx, "modid").toLowerCase(java.util.Locale.ROOT);
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();

        var rows = new java.util.LinkedHashMap<String, int[]>();   // 类型 → {总数, 提取失败, 无输入, 无主产物, OK}
        var classOf = new java.util.HashMap<String, String>();
        var badIds = new java.util.ArrayList<Component>();
        /** 类型 → 失败原因 → 条数（2026-09-17：sensei 要「失败配方类清单」，光有类名不够定位） */
        var reasons = new java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, Integer>>();
        /** 类型 → 样例（最多 3 条「id ← 原因」，我能直接去 jar 里查这些 id） */
        var samples = new java.util.HashMap<String, java.util.List<String>>();
        int all = 0;

        for (var recipe : level.getRecipeManager().getRecipes()) {
            if (recipe == null) continue;
            ResourceLocation id = recipe.getId();
            if (id == null || !modid.equals(id.getNamespace())) continue;
            all++;
            String type = String.valueOf(recipe.getType());
            int[] row = rows.computeIfAbsent(type, k -> new int[5]);
            row[0]++;
            classOf.putIfAbsent(type, recipe.getClass().getName());

            QianJiPatternData data = QianJiRecipeModel.fromRecipe(recipe, level);
            if (data == null) {
                row[1]++;
                record(reasons, samples, type, id, failReason(recipe, level));
                if (badIds.size() < 20) badIds.add(Component.literal("§c提取失败 ").append(clickableId(id))
                        .append(Component.literal(" §8(" + failReason(recipe, level) + ")")));
            } else if (data.inputs().isEmpty()) {
                row[2]++;
                record(reasons, samples, type, id, "标准 getIngredients 全空（不走标准 API 或标签解析不出物品）");
                if (badIds.size() < 20) badIds.add(Component.literal("§e无输入 ").append(clickableId(id)));
            } else if (data.primary().isEmpty()) {
                row[3]++;
                record(reasons, samples, type, id, "只有概率产出（AE2 样板要确定量）");
                if (badIds.size() < 20) badIds.add(Component.literal("§d无主产物 ").append(clickableId(id)));
            } else {
                row[4]++;
            }
        }

        if (all == 0) {
            source.sendFailure(Component.literal("§c没有 namespace = " + modid + " 的配方"));
            return 0;
        }
        say(source, "§e═══ 扫描 " + modid + "：共 " + all + " 条配方");
        say(source, "§7（§c提取失败§7 / §e无输入§7 / §d无主产物§7 / §aOK§7）");

        var ordered = new java.util.ArrayList<>(rows.entrySet());
        ordered.sort((a, b) -> {
            int badA = a.getValue()[1] + a.getValue()[2];
            int badB = b.getValue()[1] + b.getValue()[2];
            if (badA != badB) return Integer.compare(badB, badA);
            return Integer.compare(b.getValue()[0], a.getValue()[0]);
        });
        for (var e : ordered) {
            int[] r = e.getValue();
            String type = e.getKey();
            String cls = classOf.get(type);
            String flag = (r[1] + r[2]) > 0 ? "§c" : (r[3] > 0 ? "§d" : "§a");
            // 类型 id 与 Java 类名都给复制按钮：sensei 要拿「失败的配方类」清单来找我
            var row = Component.literal(flag + String.format(java.util.Locale.ROOT,
                            "%4d 条 §c失%3d §e无入%3d §d无主%3d §aOK%4d  §8",
                            r[0], r[1], r[2], r[3], r[4]))
                    .append(Component.literal(type))
                    .append(copyButton(" §8[复制]", type, "§7复制配方类型 id：" + type));
            if (cls != null) {
                row.append(copyButton(" §8[类名]", cls, "§7复制 Java 类名：" + cls));
            }
            var why = reasons.get(type);
            if (why != null && !why.isEmpty()) {
                var whyText = new StringBuilder();
                for (var w : why.entrySet()) {
                    if (!whyText.isEmpty()) whyText.append("、");
                    whyText.append(w.getKey()).append(' ').append(w.getValue());
                }
                row.append(Component.literal("  §8原因: " + whyText));
            }
            final Component line = row;
            source.sendSuccess(() -> line, false);
        }
        if (!badIds.isEmpty()) {
            say(source, "§7── 前 " + badIds.size() + " 条问题配方（点 id 填入 probe；[复制] 进剪贴板；[样板] 直接生成）──");
            for (Component line : badIds) source.sendSuccess(() -> line, false);
        }
        // 「失败配方类清单」一键带走：类型 id + Java 类名 + 条数/分类，整段复制进剪贴板
        var listText = new StringBuilder();
        int badTypes = 0;
        for (var e : ordered) {
            int[] r = e.getValue();
            if (r[1] + r[2] + r[3] == 0) continue;
            badTypes++;
            // 2026-09-17 sensei：清单要带 modid + 失败原因 + 样例 id，否则我看不出是哪种配方
            listText.append('[').append(modid).append("] ").append(e.getKey())
                    .append("  ").append(classOf.getOrDefault(e.getKey(), "?"))
                    .append("  ").append(r[0]).append(" 条（提取失败 ").append(r[1])
                    .append(" / 无输入 ").append(r[2])
                    .append(" / 无主产物 ").append(r[3]).append("）");
            var why = reasons.get(e.getKey());
            if (why != null && !why.isEmpty()) {
                listText.append("  原因: ");
                boolean firstReason = true;
                for (var w : why.entrySet()) {
                    if (!firstReason) listText.append("；");
                    firstReason = false;
                    listText.append(w.getKey()).append(' ').append(w.getValue()).append(" 条");
                }
            }
            listText.append('\n');
            var example = samples.get(e.getKey());
            if (example != null) {
                for (String line : example) listText.append("      例: ").append(line).append('\n');
            }
        }
        if (badTypes > 0) {
            final String payload = listText.toString();
            final int count = badTypes;
            source.sendSuccess(() -> Component.literal("§7── 有问题的配方类型 " + count + " 个 ")
                    .append(copyButton("§b[复制失败配方类清单]", payload,
                            "§7把 " + count + " 个类型的「id + 类名 + 条数」整段复制（粘贴给爱丽丝即可）")), false);
        }

        final int total = all;
        final int failed = rows.values().stream().mapToInt(r -> r[1]).sum();
        final int noInput = rows.values().stream().mapToInt(r -> r[2]).sum();
        source.sendSuccess(() -> Component.literal("§e小结：共 " + total + " 条，§c提取失败 " + failed
                + "§e，§e无输入 " + noInput + "§e —— 有问题的用 §f/qianji probe <配方id> §e看细节"), false);
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
                final var line = Component.literal("§8")
                        .append(clickableId(id))
                        .append(Component.literal(" §8→ §a"
                                + (data.primary().isEmpty() ? "无主产物"
                                : data.primary().get(0).stack().what().getDisplayName().getString())
                                + (data.chanced().isEmpty() ? ""
                                : " §d(+" + data.chanced().size() + " 概率产出)")));
                ctx.getSource().sendSuccess(() -> line, false);
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
