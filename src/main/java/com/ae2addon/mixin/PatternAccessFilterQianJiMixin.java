package com.ae2addon.mixin;

import appeng.api.inventories.InternalInventory;
import com.ae2addon.init.ModItems;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让**样板管理终端**的槽过滤接受千机样板（2026-09-20 sensei 需求③）。
 * <p>
 * 管理终端给每个"样板容器"套一层 {@code PatternSlotFilter}（`IAEItemFilter`）来限制能塞什么，
 * 它只认 AE2 自己的样板物品 → 我们的 {@code ae2addon:qianji_pattern} 既插不进去、也取不出来。
 * <p>
 * 处置：只对我们这个物品放行（插入与取出都放行 —— 只放插入会造成"塞进去拿不出来"）。
 * 其它物品、其它容器一律走 AE2 原逻辑。
 * <p>
 * 配套改动：{@code QianJiBE} 实现了 {@code appeng.helpers.patternprovider.PatternContainer}，
 * 所以千机才会出现在管理终端里（否则这层过滤根本没机会被调用）。
 */
@Mixin(targets = "appeng.menu.implementations.PatternAccessTermMenu$PatternSlotFilter", remap = false)
public abstract class PatternAccessFilterQianJiMixin {

    private static boolean ae2addon$isQianJiPattern(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == ModItems.QIAN_JI_PATTERN.get();
    }

    @Inject(method = "allowInsert", at = @At("HEAD"), cancellable = true)
    private void ae2addon$allowInsert(InternalInventory inv, int slot, ItemStack stack,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (ae2addon$isQianJiPattern(stack)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "allowExtract", at = @At("HEAD"), cancellable = true)
    private void ae2addon$allowExtract(InternalInventory inv, int slot, int amount,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (inv != null && slot >= 0 && slot < inv.size() && ae2addon$isQianJiPattern(inv.getStackInSlot(slot))) {
            cir.setReturnValue(true);
        }
    }
}
