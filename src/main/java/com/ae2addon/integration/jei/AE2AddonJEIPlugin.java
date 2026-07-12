package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.init.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

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
    public void registerRecipes(IRecipeRegistration registration) {
        // 添加元件信息页
        ItemStack cellStack = new ItemStack(ModItems.UNIVERSAL_STORAGE_CELL.get());

        // Mode 1 — 无限制存储
        ItemStack m1 = cellStack.copy();
        m1.getOrCreateTag().putInt("umode", 1);
        registration.addIngredientInfo(m1, VanillaTypes.ITEM_STACK,
                Component.literal("§a无限制存储模式"),
                Component.literal("§7无限容量 · 全AE类型 · 正常存取"),
                Component.literal(""),
                Component.literal("§7像普通存储元件一样使用，但容量和类型数均无上限。"),
                Component.literal("§7右键切换模式")
        );

        // Mode 2 — 自定义无限
        ItemStack m2 = cellStack.copy();
        m2.getOrCreateTag().putInt("umode", 2);
        registration.addIngredientInfo(m2, VanillaTypes.ITEM_STACK,
                Component.literal("§e自定义无限模式"),
                Component.literal("§7白名单无限 + 通用阈值"),
                Component.literal(""),
                Component.literal("§71. 设置通用阈值 — 达到阈值后该类型自动变为无限"),
                Component.literal("§7   阈值需手动配置"),
                Component.literal("§72. 添加白名单 — 指定物品自动获得无限"),
                Component.literal("§7   在配置界面右键背包物品 / 从JEI拖拽"),
                Component.literal("§73. Shift+右键面板条目可切换无限状态"),
                Component.literal("§7右键切换模式")
        );

        // Mode 3 — 全类型无限
        ItemStack m3 = cellStack.copy();
        m3.getOrCreateTag().putInt("umode", 3);
        registration.addIngredientInfo(m3, VanillaTypes.ITEM_STACK,
                Component.literal("§d全类型无限模式"),
                Component.literal("§7仅物品/流体 · 存入即无限"),
                Component.literal(""),
                Component.literal("§7存进去的任何物品和流体都会自动获得无限状态。"),
                Component.literal("§7注意：仅支持物品和流体类型，"),
                Component.literal("§7FE能量、EMC、气体等其他AE类型暂不支持。"),
                Component.literal("§7右键切换模式")
        );
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Mode 2 配置界面：支持从 JEI 拖拽物品到背包槽位
        registration.addGhostIngredientHandler(
                Mode2ConfigScreen.class,
                new Mode2ConfigGhostHandler()
        );
    }

}
