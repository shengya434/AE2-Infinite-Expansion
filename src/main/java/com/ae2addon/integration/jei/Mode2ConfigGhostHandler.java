package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.network.Mode2ConfigPacket;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode 2 配置界面的 Ghost Ingredient 处理器
 * <p>
 * 允许玩家从 JEI 面板直接拖拽物品到背包槽位，
 * 从而快速将物品加入白名单。
 */
public class Mode2ConfigGhostHandler implements IGhostIngredientHandler<Mode2ConfigScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(Mode2ConfigScreen screen, ITypedIngredient<I> ingredient, boolean start) {
        List<Target<I>> targets = new ArrayList<>();

        I ing = ingredient.getIngredient();
        if (!(ing instanceof ItemStack)) {
            return targets;
        }

        // 背包区域 (9x3 + 1x9 快捷栏)
        // 参照 Mode2ConfigMenu 中槽位的布局
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();

        // 主背包 3行 (y=166起)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int x = guiLeft + 48 + col * 18;
                int y = guiTop + 166 + row * 18;
                targets.add(new AddToWhitelistTarget<>(x, y, 18, 18));
            }
        }

        // 快捷栏 1行 (y=224起)
        for (int col = 0; col < 9; col++) {
            int x = guiLeft + 48 + col * 18;
            int y = guiTop + 224;
            targets.add(new AddToWhitelistTarget<>(x, y, 18, 18));
        }

        return targets;
    }

    @Override
    public void onComplete() {
        // 不需要额外操作
    }

    /**
     * 拖拽目标：将 JEI 中的 ItemStack 作为白名单加入请求发送到服务端
     */
    private static class AddToWhitelistTarget<I> implements Target<I> {
        private final Rect2i area;

        public AddToWhitelistTarget(int x, int y, int w, int h) {
            this.area = new Rect2i(x, y, w, h);
        }

        @Override
        public Rect2i getArea() {
            return area;
        }

        @Override
        public void accept(I ingredient) {
            if (!(ingredient instanceof ItemStack stack)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 直接将 JEI 拖拽的物品作为白名单添加请求发到服务端
            ItemStack toAdd = stack.copy();
            toAdd.setCount(1);
            AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(toAdd));
        }
    }
}
