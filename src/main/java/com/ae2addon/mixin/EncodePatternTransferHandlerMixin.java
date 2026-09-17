package com.ae2addon.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.ae2addon.AE2Addon;
import com.ae2addon.integration.jei.QianJiRecipeCategory;
import com.ae2addon.network.QianJiPatternPacket;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 编码终端的「+」（JEI 配方传输）收敛（2026-09-17 sensei 定）。
 * <p>
 * AE2 的 {@code EncodePatternTransferHandler.transferRecipe} 会**当场编码**：
 * 合成配方 → AE2 合成样板；其它 → AE2 处理样板。以前我们的
 * {@code ProcessingPatternItemMixin} 会在最外层把结果无差别换成千机样板。
 * <p>
 * 现在改成：**只有当这次传输来自「千机·自用配方页」时**，才由我们发自己的样板包
 * （{@link QianJiPatternPacket}，与页面上的「编码」按钮完全同一条路），
 * 并让 AE2 的传输逻辑直接返回（不再编 AE2 样板）。
 * 别的页面照旧走 AE2 原生逻辑。
 * <p>
 * ⚠ JEI 会先用 {@code doTransfer=false} 调一次做可行性检查，那时**不能发包**（否则鼠标一划就发一堆）。
 */
@Mixin(value = appeng.integration.modules.jei.transfer.EncodePatternTransferHandler.class, remap = false)
public abstract class EncodePatternTransferHandlerMixin {

    @Inject(method = "transferRecipe", at = @At("HEAD"), cancellable = true)
    private void ae2addon$qianjiPageOnly(PatternEncodingTermMenu menu,
                                         Object recipe,
                                         IRecipeSlotsView recipeSlotsView,
                                         Player player,
                                         boolean maxTransfer,
                                         boolean doTransfer,
                                         CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (!(recipe instanceof QianJiRecipeCategory.Entry entry)) return;   // 别的配方页 → AE2 原逻辑
        if (entry.recipeId() == null || entry.recipeId().isEmpty()) return;
        if (!doTransfer) {
            // 可行性检查：告诉 JEI「这个页面能传」，真正的动作留到点击时
            cir.setReturnValue(null);
            return;
        }
        AE2Addon.NETWORK.sendToServer(new QianJiPatternPacket(entry.recipeId(), entry.variant()));
        cir.setReturnValue(null);   // 不走 AE2 的编码流程（样板由服务端按配方 id 生成）
    }
}
