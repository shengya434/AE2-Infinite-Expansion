package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.ae2addon.AE2Addon;
import com.ae2addon.gui.ModeSelectScreen;
import com.ae2addon.network.Mode2ConfigPacket;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选择存储模式」界面（{@link ModeSelectScreen}）的**拒收列表**：JEI 拖拽支持
 * （2026-10-13 sensei：拒收列表可编辑内容过少）。
 * <p>
 * 拖拽目标 = 界面上那块拒收列表（3 行）。从 JEI 把物品/流体/化学物丢进去 = 把该键**加入拒收**。
 * <p>
 * 手势分工（三处互相不冲突）：
 * <ul>
 *   <li><b>JEI 拖入列表</b> → 加入拒收（本类）。已在拒收里的直接忽略 —— 拖入语义是「加入」，
 *       不能走 toggle 把它翻成「允许」（那要用左键点条目）</li>
 *   <li><b>左键点条目</b> → 照旧切换 拒收/允许（{@code ModeSelectScreen.mouseClicked}，type 7）</li>
 *   <li><b>Shift+左键点条目</b> → 从列表删除（同一处，type 22 = clearReject）</li>
 * </ul>
 * 协议**没有新增包类型**：加用现有的 type 7、删除用现有的 type 22。
 */
public class ModeSelectGhostHandler implements IGhostIngredientHandler<ModeSelectScreen> {

    /**
     * 拒收列表命中区（与 {@code ModeSelectScreen.mouseClicked} 里那块一致：
     * x 10..178、y 171..213，相对 GUI 左上角）。
     */
    private static final int LIST_X = 10;
    private static final int LIST_Y = 171;
    private static final int LIST_W = 168;
    private static final int LIST_H = 42;

    @Override
    public <I> List<Target<I>> getTargetsTyped(ModeSelectScreen screen, ITypedIngredient<I> ingredient,
            boolean start) {
        List<Target<I>> targets = new ArrayList<>();
        if (toKey(ingredient.getIngredient()) == null) {
            return targets;   // 不是我们认识的成分（物品/流体/化学物之外）
        }
        targets.add(new AddToRejectTarget<>(screen, new Rect2i(
                screen.getGuiLeft() + LIST_X, screen.getGuiTop() + LIST_Y, LIST_W, LIST_H)));
        return targets;
    }

    @Override
    public void onComplete() {
        // 不需要额外操作
    }

    /** JEI 成分 → AEKey；不认识或转换失败返回 null。 */
    private static AEKey toKey(Object ingredient) {
        if (ingredient instanceof ItemStack stack) {
            return stack.isEmpty() ? null : AEItemKey.of(stack);
        }
        if (ingredient instanceof net.minecraftforge.fluids.FluidStack fluid) {
            return AEFluidKey.of(fluid);
        }
        // 化学物（Mekanism 系）：与 Mode2 的 ghost handler 同一套判断/转换
        if (net.minecraftforge.fml.ModList.get().isLoaded("mekanism")
                && ingredient instanceof mekanism.api.chemical.ChemicalStack<?> chemical) {
            return com.ae2addon.compat.MekanismChemCompat.keyOfChemical(chemical);
        }
        return null;
    }

    /** 拖拽目标：把拖进来的成分作为「拒收」键发给服务端。 */
    private static class AddToRejectTarget<I> implements Target<I> {

        private final ModeSelectScreen screen;
        private final Rect2i area;

        AddToRejectTarget(ModeSelectScreen screen, Rect2i area) {
            this.screen = screen;
            this.area = area;
        }

        @Override
        public Rect2i getArea() {
            return area;
        }

        @Override
        public void accept(I ingredient) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            AEKey key = toKey(ingredient);
            if (key == null) {
                return;
            }
            if (screen.isRejectingKey(key)) {
                return;   // 已经是拒收态 → 不切换（见类注释）
            }
            AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(7, key.toTagGeneric()));
        }
    }
}
