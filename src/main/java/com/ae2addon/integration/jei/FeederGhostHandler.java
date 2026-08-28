package com.ae2addon.integration.jei;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.ae2addon.AE2Addon;
import com.ae2addon.gui.InfiniteInterfaceMenu;
import com.ae2addon.gui.InfiniteInterfaceScreen;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * ME接口（无限级）标记槽的 JEI 拖取处理器（2026-08-28 sensei 要求）：
 * 从 JEI 拖取物品/流体/气体到标记槽 = 直接标记（不消耗、不放入）。
 * <p>
 * ghost accept 在客户端执行，通过 FeederMarkPacket 通知服务端。
 * 气体（Mekanism ChemicalStack）仅在 mekanism 加载时参与 instanceof（短路保护）。
 */
public class FeederGhostHandler implements IGhostIngredientHandler<InfiniteInterfaceScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(InfiniteInterfaceScreen screen,
            ITypedIngredient<I> ingredient, boolean start) {
        List<Target<I>> targets = new ArrayList<>();
        I ing = ingredient.getIngredient();
        if (!isSupported(ing)) {
            return targets;
        }
        // 标记槽（区间随容量卡动态）
        InfiniteInterfaceMenu menu = screen.getMenu();
        int markerStart = menu.markerSlotStart();
        int markerEnd = menu.markerSlotEnd();
        for (var slot : menu.getSlotList()) {
            if (slot.index >= markerStart && slot.index < markerEnd) {
                targets.add(new MarkTarget<>(screen.getGuiLeft() + slot.x,
                        screen.getGuiTop() + slot.y, slot.index - markerStart));
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
        // 无需额外操作
    }

    /** 支持物品/流体/Mekanism 化学物（气体等）；Mekanism 未加载时不会命中化学物分支。 */
    private static boolean isSupported(Object ing) {
        if (ing instanceof ItemStack || ing instanceof FluidStack) {
            return true;
        }
        return ModList.get().isLoaded("mekanism")
                && ing instanceof mekanism.api.chemical.ChemicalStack<?>;
    }

    private static class MarkTarget<I> implements Target<I> {
        private final Rect2i area;
        private final int markerIndex;

        MarkTarget(int x, int y, int markerIndex) {
            this.area = new Rect2i(x, y, 16, 16);
            this.markerIndex = markerIndex;
        }

        @Override
        public Rect2i getArea() {
            return area;
        }

        @Override
        public void accept(I ingredient) {
            AEKey key = null;
            if (ingredient instanceof ItemStack stack) {
                key = AEItemKey.of(stack);
            } else if (ingredient instanceof FluidStack fluid) {
                key = AEFluidKey.of(fluid);
            } else if (ModList.get().isLoaded("mekanism")
                    && ingredient instanceof mekanism.api.chemical.ChemicalStack<?> chemical) {
                key = com.ae2addon.compat.MekanismGasCompat.keyOfChemical(chemical);
            }
            if (key == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof InfiniteInterfaceScreen) {
                AE2Addon.NETWORK.sendToServer(
                        new com.ae2addon.network.FeederMarkPacket(markerIndex, key));
            }
        }
    }
}
