package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.init.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * AE2 Addon JEI 集成主插件
 * <p>
 * 功能：
 * 1. Ghost Ingredient — Mode 2 配置界面拖拽物品加白名单
 * 2. 元件信息页 — JEI 中显示元件各模式说明
 */
@JeiPlugin
public class AE2AddonJEIPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(AE2Addon.MODID, "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new IntegratedCPURecipeCategory(registration.getJeiHelpers().getGuiHelper()),
                new QianJiRecipeCategory(registration.getJeiHelpers().getGuiHelper())
        );
    }

    /** 千机 JEI 页：一次注册多少条（0 / 负数 = 不限量；2026-09-15 sensei：先完全放开） */
    private static final int MAX_QIANJI_RECIPES = Integer.MAX_VALUE;

    private void registerQianJiRecipes(IRecipeRegistration registration) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            AE2Addon.LOGGER.warn("[ae2addon] JEI: 客户端世界未就绪，跳过千机配方页");
            return;
        }
        long started = System.currentTimeMillis();

        // 配方来源 = RecipeManager 全量 + 「额外配方来源」
        // （GT 的运行时配方不在 RecipeManager 里，见 GregTechRuntimeCompat）
        // 按稳定 id 去重：两个来源重叠时以 RecipeManager 为准
        var sources = new java.util.LinkedHashMap<String, Recipe<?>>();
        for (var recipe : level.getRecipeManager().getRecipes()) {
            sources.putIfAbsent(com.ae2addon.compat.GregTechRuntimeCompat.stableId(recipe), recipe);
        }
        int extra = 0;
        for (var recipe : com.ae2addon.recipe.QianJiRecipeModel.extraRecipes()) {
            if (sources.putIfAbsent(com.ae2addon.compat.GregTechRuntimeCompat.stableId(recipe), recipe) == null) {
                extra++;
            }
        }

        var entries = new java.util.ArrayList<QianJiRecipeCategory.Entry>();
        int considered = 0;
        int mek = 0;
        int variantCount = 0;
        for (var recipe : sources.values()) {
            if (entries.size() >= MAX_QIANJI_RECIPES) break;
            considered++;
            // 2026-09-15：一条配方可能出多张样板（Create 序列装配 = 全链 + 每步 + 收尾）
            var all = com.ae2addon.recipe.QianJiRecipeModel.fromRecipeAll(recipe, level.registryAccess());
            if (all.isEmpty()) continue;
            boolean counted = false;
            for (var variant : all) {
                var data = variant.data();
                if (data == null) continue;
                if ((data.primary().isEmpty() && data.chanced().isEmpty()) || data.inputs().isEmpty()) continue;
                entries.add(QianJiRecipeCategory.of(recipe, variant.index(), variant.label(), data));
                variantCount++;
                // 序列装配诊断（只有 3 条，量小）：把提取出的输入/产出打出来，便于核对
                if (String.valueOf(data.machine()).contains("sequenced_assembly")) {
                    var sb = new StringBuilder();
                    for (var slot : data.inputs()) {
                        if (slot.options().isEmpty()) continue;
                        var first = slot.options().get(0);
                        sb.append(first.what().getDisplayName().getString())
                                .append('×').append(first.amount())
                                .append(slot.catalyst() ? "(不消耗)" : "");
                        if (slot.options().size() > 1) {
                            sb.append("(候选");
                            int shown = 0;
                            for (var option : slot.options()) {
                                if (shown++ > 0) sb.append(',');
                                sb.append(option.what().getDisplayName().getString());
                                if (shown >= 6) { sb.append("…"); break; }
                            }
                            sb.append(')');
                        }
                        sb.append(" | ");
                    }
                    var out = new StringBuilder();
                    for (var p : data.primary()) {
                        out.append(p.stack().what().getDisplayName().getString())
                                .append('×').append(p.stack().amount()).append(" | ");
                    }
                    var red = new StringBuilder();
                    for (var item : com.ae2addon.compat.CreateSequencedCompat.chain(recipe, level.registryAccess()) == null
                            ? java.util.Set.<net.minecraft.world.item.Item>of()
                            : com.ae2addon.compat.CreateSequencedCompat.chain(recipe, level.registryAccess()).redundantItems()) {
                        red.append(item.getDescription().getString()).append(',');
                    }
                    AE2Addon.LOGGER.info("[ae2addon][装配] {} 输入=[{}] 主产物=[{}] 概率产出={}种 已剔除中间产物=[{}]",
                            data.recipeId(), sb, out, data.chanced().size(), red);
                    AE2Addon.LOGGER.info("[ae2addon][装配·步骤] {} 原始步骤={}",
                            data.recipeId(),
                            com.ae2addon.compat.CreateSequencedCompat.describeSteps(recipe));
                }
                if (!counted) {
                    counted = true;
                    if (data.machine() != null && data.machine().startsWith("mekanism")) mek++;
                }
            }
        }
        registration.addRecipes(QianJiRecipeCategory.TYPE, entries);
        AE2Addon.LOGGER.info("[ae2addon] JEI 千机配方页：注册 {} 条（变体 {} 张；扫描 {} 条，其中 MEK {} 条、GT 运行时额外 {} 条，耗时 {} ms）",
                entries.size(), variantCount, considered, mek, extra, System.currentTimeMillis() - started);
    }

    @Override
    public void registerRecipeCatalysts(mezz.jei.api.registration.IRecipeCatalystRegistration registration) {
        // 千机就是这些配方的『工作台』
        registration.addRecipeCatalyst(new ItemStack(com.ae2addon.init.ModBlocks.QIAN_JI.get()),
                QianJiRecipeCategory.TYPE);
        // 2026-09-17 sensei：合成 / 熔炼 / 锻造台这三类配方也在千机页里浏览，
        // 所以把对应的原版机器也注册成催化剂 —— 在 JEI 里点/右键它们就能直接筛出对应的千机配方。
        // （页面上那个「机器」栏显示的就是同一张图；暂时只覆盖这三类，效果好再扩到各 mod 机器）
        for (var machineItem : new net.minecraft.world.item.Item[]{
                net.minecraft.world.item.Items.CRAFTING_TABLE,
                net.minecraft.world.item.Items.FURNACE,
                net.minecraft.world.item.Items.BLAST_FURNACE,
                net.minecraft.world.item.Items.SMOKER,
                net.minecraft.world.item.Items.CAMPFIRE,
                net.minecraft.world.item.Items.SMITHING_TABLE}) {
            registration.addRecipeCatalyst(new ItemStack(machineItem), QianJiRecipeCategory.TYPE);
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // 千机自用配方页（自有样板体系的浏览+编码入口）
        registerQianJiRecipes(registration);

        // 集成 CPU 结构预览页
        registration.addRecipes(
                IntegratedCPURecipeCategory.TYPE,
                List.of(new IntegratedCPURecipeCategory.IntegratedCPURecipe())
        );

        // 添加元件信息页
        ItemStack cellStack = new ItemStack(ModItems.UNIVERSAL_STORAGE_CELL.get());

        // Mode 1 — 无限制存储
        ItemStack m1 = cellStack.copy();
        m1.getOrCreateTag().putInt("umode", 1);
        registration.addIngredientInfo(m1, VanillaTypes.ITEM_STACK,
                Component.translatable("gui.ae2addon.jei.m1.title"),
                Component.translatable("gui.ae2addon.jei.m1.desc1"),
                Component.literal(""),
                Component.translatable("gui.ae2addon.jei.m1.desc2"),
                Component.translatable("gui.ae2addon.jei.switch_hint")
        );

        // Mode 2 — 自定义无限
        ItemStack m2 = cellStack.copy();
        m2.getOrCreateTag().putInt("umode", 2);
        registration.addIngredientInfo(m2, VanillaTypes.ITEM_STACK,
                Component.translatable("gui.ae2addon.jei.m2.title"),
                Component.translatable("gui.ae2addon.jei.m2.desc1"),
                Component.literal(""),
                Component.translatable("gui.ae2addon.jei.m2.step1"),
                Component.translatable("gui.ae2addon.jei.m2.step1b"),
                Component.translatable("gui.ae2addon.jei.m2.step2"),
                Component.translatable("gui.ae2addon.jei.m2.step2b"),
                Component.translatable("gui.ae2addon.jei.m2.step3"),
                Component.translatable("gui.ae2addon.jei.switch_hint")
        );

        // Mode 3 — 全类型无限
        ItemStack m3 = cellStack.copy();
        m3.getOrCreateTag().putInt("umode", 3);
        registration.addIngredientInfo(m3, VanillaTypes.ITEM_STACK,
                Component.translatable("gui.ae2addon.jei.m3.title"),
                Component.translatable("gui.ae2addon.jei.m3.desc1"),
                Component.literal(""),
                Component.translatable("gui.ae2addon.jei.m3.desc2"),
                Component.translatable("gui.ae2addon.jei.m3.desc3"),
                Component.translatable("gui.ae2addon.jei.m3.desc4"),
                Component.translatable("gui.ae2addon.jei.switch_hint")
        );
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Mode 2 配置界面：支持从 JEI 拖拽物品到背包槽位
        registration.addGhostIngredientHandler(
                Mode2ConfigScreen.class,
                new Mode2ConfigGhostHandler()
        );

        // ME接口（无限级）标记槽：JEI 拖取物品/流体/气体直接标记
        registration.addGhostIngredientHandler(
                com.ae2addon.gui.InfiniteInterfaceScreen.class,
                new FeederGhostHandler()
        );
    }

}
