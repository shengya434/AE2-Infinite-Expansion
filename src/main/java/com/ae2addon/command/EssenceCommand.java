package com.ae2addon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 测试指令：/ae2essence —— 造「无限 xxx 精华」与「无限 xxx 元件」。
 * <p>
 * 为什么需要它：精华与元件都是**一个物品 + NBT 绑定目标 key**，
 * 所以原版 {@code /give} 给不出带绑定的实例；而"输入全是催化剂"的千机配方
 * 不一定手边就有。这条指令让 S1/S2/S4 可以独立验证，不必先凑齐 S3 的前置。
 * <p>
 * 用法：
 * <ul>
 *   <li>{@code /ae2essence essence <物品注册名> [数量]} —— 给绑定的精华（如 minecraft:stone）</li>
 *   <li>{@code /ae2essence fluidessence <流体注册名> [数量]} —— 流体的精华（如 minecraft:lava）</li>
 *   <li>{@code /ae2essence cell <物品注册名>} —— 直接给成品元件（跳过合成，测存储用）</li>
 * </ul>
 */
public class EssenceCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ae2essence")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("essence")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> giveEssence(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), 1, false))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                        .executes(ctx -> giveEssence(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"), false)))))
                .then(Commands.literal("fluidessence")
                        .then(Commands.argument("fluid", StringArgumentType.string())
                                .executes(ctx -> giveEssence(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "fluid"), 1, true))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                        .executes(ctx -> giveEssence(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "fluid"),
                                                IntegerArgumentType.getInteger(ctx, "count"), true)))))
                .then(Commands.literal("cell")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(ctx -> giveCell(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), false)))
                        .then(Commands.argument("fluid", StringArgumentType.string())
                                .executes(ctx -> giveCell(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "fluid"), true))))
                // 调试：强制所有千机自有样板按"输入全是催化剂"处理（只加精华，不改产出/消耗）
                .then(Commands.literal("forceessence")
                        .then(Commands.literal("on").executes(ctx -> setForce(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setForce(ctx.getSource(), false)))
                        .executes(ctx -> setForce(ctx.getSource(),
                                !com.ae2addon.crafting.QianJiByproducts.debugForceAllEssences)))
                // 自检：一条命令跑完配方与存储的全部断言（不用手摆工作台）
                .then(Commands.literal("selftest").executes(ctx -> selfTest(ctx.getSource())))
        );
    }

    // ── 自检 ──

    /**
     * 自检：把「精华 + 外壳 → 元件」的配方逻辑与「元件单 key 无限存储」的存储逻辑
     * **在运行中的游戏里真跑一遍**（不是读代码推断），逐条打印 ✓/✗。
     * <p>
     * 为什么需要它：AI 看不到游戏画面，手工摆工作台又慢；这样 sensei 一条命令就能拿到
     * "①②③ 的代码路径到底通不通"的证据。
     */
    private static int selfTest(CommandSourceStack src) {
        var level = src.getLevel();
        int pass = 0;
        int total = 0;
        var out = new java.util.ArrayList<String>();

        var stone = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.STONE);
        var dirt = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.DIRT);
        var lava = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.LAVA);

        ItemStack stoneEss = com.ae2addon.item.InfiniteEssenceItem.make(stone, 1);
        ItemStack lavaEss = com.ae2addon.item.InfiniteEssenceItem.make(lava, 1);
        ItemStack itemHousing = new ItemStack(
                appeng.core.definitions.AEItems.ITEM_CELL_HOUSING.asItem());
        ItemStack fluidHousing = new ItemStack(
                appeng.core.definitions.AEItems.FLUID_CELL_HOUSING.asItem());

        var recipe = new com.ae2addon.recipe.EssenceToCellRecipe(
                new ResourceLocation("ae2addon", "essence_to_cell"));

        // ① 物品精华 + 物品外壳 → 物品元件（且绑定 stone）
        total++;
        ItemStack r1 = assemble(recipe, level, stoneEss, itemHousing);
        if (!r1.isEmpty()
                && r1.getItem() == com.ae2addon.init.ModItems.INFINITE_ITEM_CELL.get()
                && stone.equals(com.ae2addon.item.BoundInfiniteCellItem.getBoundKey(r1))) {
            pass++;
            out.add("§a[通过] 物品：石头精华 + 物品外壳 → " + r1.getHoverName().getString());
        } else {
            out.add("§c[失败] 物品：石头精华 + 物品外壳 未产出正确元件（结果=" + describe(r1) + "）");
        }

        // ② 流体精华 + 流体外壳 → 流体元件（且绑定 lava）
        total++;
        ItemStack r2 = assemble(recipe, level, lavaEss, fluidHousing);
        if (!r2.isEmpty()
                && r2.getItem() == com.ae2addon.init.ModItems.INFINITE_FLUID_CELL.get()
                && lava.equals(com.ae2addon.item.BoundInfiniteCellItem.getBoundKey(r2))) {
            pass++;
            out.add("§a[通过] 流体：岩浆精华 + 流体外壳 → " + r2.getHoverName().getString());
        } else {
            out.add("§c[失败] 流体：岩浆精华 + 流体外壳 未产出正确元件（结果=" + describe(r2) + "）");
        }

        // ③ 类型不匹配（物品精华 + 流体外壳）必须**不**匹配
        total++;
        ItemStack r3 = assemble(recipe, level, stoneEss, fluidHousing);
        if (r3.isEmpty()) {
            pass++;
            out.add("§a[通过] 类型不匹配被拒（石头精华 + 流体外壳）");
        } else {
            out.add("§c[失败] 类型不匹配竟然产出：" + describe(r3));
        }

        // ④ 混入第三个物品必须**不**匹配
        total++;
        ItemStack r4 = assemble(recipe, level, stoneEss, itemHousing, new ItemStack(
                net.minecraft.world.item.Items.DIRT));
        if (r4.isEmpty()) {
            pass++;
            out.add("§a[通过] 混入多余物品被拒");
        } else {
            out.add("§c[失败] 混入多余物品竟然产出：" + describe(r4));
        }

        // ⑤ 存储：绑定 stone 的元件 → 只认 stone、无限提取、全额存入
        total++;
        ItemStack cell = com.ae2addon.item.BoundInfiniteCellItem.make(
                com.ae2addon.init.ModItems.INFINITE_ITEM_CELL.get(), stone);
        var inv = new com.ae2addon.cell.BoundInfiniteCellInventory(cell, null);
        var reported = new appeng.api.stacks.KeyCounter();
        inv.getAvailableStacks(reported);
        long avail = reported.get(stone);
        long give = inv.extract(stone, 123_456L, appeng.api.config.Actionable.SIMULATE, null);
        long giveOther = inv.extract(dirt, 1L, appeng.api.config.Actionable.SIMULATE, null);
        long take = inv.insert(stone, 999_999L, appeng.api.config.Actionable.MODULATE, null);
        long takeOther = inv.insert(dirt, 1L, appeng.api.config.Actionable.MODULATE, null);
        if (avail > 0 && give == 123_456L && giveOther == 0
                && take == 999_999L && takeOther == 0
                && inv.getStatus() == appeng.api.storage.cells.CellState.NOT_EMPTY) {
            pass++;
            out.add("§a[通过] 存储：报量=" + avail + "，提取 123456=" + give
                    + "，存入 999999=" + take + "，其它物品拒收(" + giveOther + "/" + takeOther + ")");
        } else {
            out.add("§c[失败] 存储：报量=" + avail + " 提取=" + give + "/其它=" + giveOther
                    + " 存入=" + take + "/其它=" + takeOther + " 状态=" + inv.getStatus());
        }

        // ⑥ 化学品（Applied-Mekanistics 可选）：装了才测
        var chemHousing = com.ae2addon.compat.ChemicalCompat.chemicalHousing();
        var chemKey = com.ae2addon.compat.ChemicalCompat.exampleChemicalKey();
        if (chemHousing != null && chemKey != null) {
            total++;
            ItemStack chemEssence = com.ae2addon.item.InfiniteEssenceItem.make(chemKey, 1);
            ItemStack r6 = assemble(recipe, level, chemEssence, new ItemStack(chemHousing));
            var bound6 = com.ae2addon.item.BoundInfiniteCellItem.getBoundKey(r6);
            if (!r6.isEmpty()
                    && r6.getItem() == com.ae2addon.init.ModItems.INFINITE_CHEMICAL_CELL.get()
                    && bound6 != null && chemKey.getType().equals(bound6.getType())) {
                pass++;
                out.add("§a[通过] 化学品：" + chemKey.getDisplayName().getString()
                        + "精华 + 化学品外壳 → " + r6.getHoverName().getString());
            } else {
                out.add("§c[失败] 化学品：未产出正确元件（结果=" + describe(r6) + "）");
            }
        } else {
            out.add("§7[跳过] 化学品：未装 Applied-Mekanistics（或键类型 id 不是 appmek:chemical）");
        }

        String header = "§e[无限元件自检] §f" + pass + "/" + total + " 通过";
        src.sendSuccess(() -> Component.literal(header), true);
        for (String line : out) {
            src.sendSuccess(() -> Component.literal(line), false);
            com.ae2addon.AE2Addon.LOGGER.info("[ae2addon][自检] {}",
                    line.replaceAll("§.", ""));
        }
        return pass;
    }

    /** 把给定物品摆进合成格跑一次配方（返回产物，未匹配则空） */
    private static ItemStack assemble(com.ae2addon.recipe.EssenceToCellRecipe recipe,
                                      net.minecraft.world.level.Level level, ItemStack... inputs) {
        var grid = new TestGrid(9);
        for (int i = 0; i < inputs.length; i++) {
            grid.setItem(i, inputs[i]);
        }
        if (!recipe.matches(grid, level)) {
            return ItemStack.EMPTY;
        }
        return recipe.assemble(grid, level.registryAccess());
    }

    private static String describe(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? "空"
                : stack.getHoverName().getString() + "×" + stack.getCount();
    }

    /** 极简合成格（只用于自检：不接菜单、不需要 slotsChanged） */
    private static final class TestGrid
            implements net.minecraft.world.inventory.CraftingContainer {
        private final net.minecraft.core.NonNullList<ItemStack> items;

        TestGrid(int size) {
            this.items = net.minecraft.core.NonNullList.withSize(size, ItemStack.EMPTY);
        }

        @Override public int getWidth() { return items.size(); }
        @Override public int getHeight() { return 1; }
        @Override public java.util.List<ItemStack> getItems() { return items; }
        @Override public int getContainerSize() { return items.size(); }
        @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
        @Override public ItemStack getItem(int index) { return items.get(index); }
        @Override public ItemStack removeItem(int index, int count) {
            return net.minecraft.world.ContainerHelper.removeItem(items, index, count);
        }
        @Override public ItemStack removeItemNoUpdate(int index) {
            return net.minecraft.world.ContainerHelper.takeItem(items, index);
        }
        @Override public void setItem(int index, ItemStack stack) { items.set(index, stack); }
        @Override public void setChanged() { }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
        @Override public void clearContent() { items.clear(); }
        @Override public void fillStackedContents(
                net.minecraft.world.entity.player.StackedContents contents) {
            for (ItemStack s : items) {
                contents.accountSimpleStack(s);
            }
        }
    }

    /**
     * 调试开关：真实配方里"输入全是催化剂"的很少见，为了能立刻验证精华链路
     * （生成 → 结算 → 入网），允许强制触发。日志里会明写"调试强制"，不会和真判定混淆。
     */
    private static int setForce(CommandSourceStack src, boolean on) {
        com.ae2addon.crafting.QianJiByproducts.debugForceAllEssences = on;
        src.sendSuccess(() -> Component.literal(on
                ? "§e精华调试强制：§a开§7（所有千机自有样板都会追加精华，仅用于测试）"
                : "§e精华调试强制：§c关§7（回到「输入全是催化剂」的真实判定）"), true);
        return 1;
    }

    /** 造精华并塞给玩家（背包满则掉在脚下） */
    private static int giveEssence(CommandSourceStack src, String id, int count, boolean fluid) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("该指令只能由玩家执行"));
            return 0;
        }
        appeng.api.stacks.AEKey key = resolve(id, fluid);
        if (key == null) {
            src.sendFailure(Component.literal("找不到" + (fluid ? "流体" : "物品") + ": " + id));
            return 0;
        }
        ItemStack stack = com.ae2addon.item.InfiniteEssenceItem.make(key, count);
        give(player, stack);
        src.sendSuccess(() -> Component.literal("已给出 §d" + stack.getHoverName().getString()
                + "§r × " + count + "（绑定 " + key.getId() + "）"), false);
        return 1;
    }

    /** 直接造成品元件（跳过合成，用于单独验证存储） */
    private static int giveCell(CommandSourceStack src, String id, boolean fluid) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("该指令只能由玩家执行"));
            return 0;
        }
        appeng.api.stacks.AEKey key = resolve(id, fluid);
        if (key == null) {
            src.sendFailure(Component.literal("找不到" + (fluid ? "流体" : "物品") + ": " + id));
            return 0;
        }
        var cell = fluid
                ? com.ae2addon.init.ModItems.INFINITE_FLUID_CELL.get()
                : com.ae2addon.init.ModItems.INFINITE_ITEM_CELL.get();
        ItemStack stack = com.ae2addon.item.BoundInfiniteCellItem.make(cell, key);
        give(player, stack);
        src.sendSuccess(() -> Component.literal("已给出 §d" + stack.getHoverName().getString()
                + "§r（绑定 " + key.getId() + "）"), false);
        return 1;
    }

    /** 解析注册名 → AEKey（物品或流体） */
    private static appeng.api.stacks.AEKey resolve(String id, boolean fluid) {
        ResourceLocation rl;
        try {
            rl = new ResourceLocation(id);
        } catch (RuntimeException e) {
            return null;
        }
        if (fluid) {
            var f = BuiltInRegistries.FLUID.get(rl);
            return f == null ? null : appeng.api.stacks.AEFluidKey.of(f);
        }
        var item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        return appeng.api.stacks.AEItemKey.of(item);
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
    }
}
