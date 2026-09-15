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
        var entries = new java.util.ArrayList<QianJiRecipeCategory.Entry>();
        int considered = 0;
        int mek = 0;
        for (var recipe : level.getRecipeManager().getRecipes()) {
            if (entries.size() >= MAX_QIANJI_RECIPES) break;
            considered++;
            var data = com.ae2addon.recipe.QianJiRecipeModel.fromRecipe(recipe, level);
            if (data == null || data.primary().isEmpty() || data.inputs().isEmpty()) continue;
            if (data.machine() != null && data.machine().startsWith("mekanism")) mek++;
            entries.add(QianJiRecipeCategory.of(recipe, data));
        }
        registration.addRecipes(QianJiRecipeCategory.TYPE, entries);
        AE2Addon.LOGGER.info("[ae2addon] JEI 千机配方页：注册 {} 条（扫描 {} 条，其中 MEK {} 条，耗时 {} ms）",
                entries.size(), considered, mek, System.currentTimeMillis() - started);
    }

    @Override
    public void registerRecipeCatalysts(mezz.jei.api.registration.IRecipeCatalystRegistration registration) {
        // 千机就是这些配方的『工作台』
        registration.addRecipeCatalyst(new ItemStack(com.ae2addon.init.ModBlocks.QIAN_JI.get()),
                QianJiRecipeCategory.TYPE);
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
