package com.ae2addon.mixin;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.core.localization.Tooltips;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.ae2addon.AE2Addon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CPU 选择列表显示美化：集成 CPU 的「无限存储 / 拉满并行」渲染成 ∞。
 * <p>
 * 哨兵值：
 * - 存储：Long.MAX_VALUE（IntegratedCPUBE.getStorageBytes / getAvailableStorage）
 * - 并行：Integer.MAX_VALUE−1（getCoProcessors，AE2 内部 +1 不溢出）
 * <p>
 * 思路来自 OmniSequence-Transfinite 的 CPUSelectionListMixin。
 * <p>
 * 共存说明：OmniSequence 也 @WrapOperation 了 drawBackgroundLayer/getTooltip 的
 * 同一批调用点。这里统一用 @WrapOperation + priority 1100（&gt; Omni 默认 1000），
 * 我们的 handler 在链最外层最后决定返回值，互不干扰。
 */
@Mixin(value = CPUSelectionList.class, priority = 1100, remap = false)
public abstract class CPUSelectionListMixin {

    @Unique
    private static final String AE2ADDON_INFINITE_TEXT = "∞";

    @Inject(method = "formatStorage", at = @At("HEAD"), cancellable = true)
    private void ae2addon$formatInfiniteStorage(
            CraftingStatusMenu.CraftingCpuListEntry cpu,
            CallbackInfoReturnable<String> callback) {
        if (!ae2addon$diagLogged) {
            ae2addon$diagLogged = true;
            AE2Addon.LOGGER.info("[ae2addon] formatStorage 注入生效！storage={}", cpu.storage());
        }
        if (cpu.storage() == Long.MAX_VALUE) {
            callback.setReturnValue(AE2ADDON_INFINITE_TEXT);
        }
    }

    @Unique
    private static boolean ae2addon$diagLogged;

    @WrapOperation(method = "drawBackgroundLayer",
            at = @At(value = "INVOKE",
                    target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"))
    private String ae2addon$formatInfiniteParallelism(int value,
            Operation<String> original) {
        return value == Integer.MAX_VALUE - 1
                ? AE2ADDON_INFINITE_TEXT
                : original.call(value);
    }

    @WrapOperation(method = "getTooltip",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;ofNumber(J)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent ae2addon$tooltipInfiniteParallelism(long value,
            Operation<MutableComponent> original) {
        return value == Integer.MAX_VALUE - 1
                ? ae2addon$infiniteTooltipValue()
                : original.call(value);
    }

    @WrapOperation(method = "getTooltip",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;ofBytes(J)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent ae2addon$tooltipInfiniteStorage(long value,
            Operation<MutableComponent> original) {
        return value == Long.MAX_VALUE
                ? ae2addon$infiniteTooltipValue()
                : original.call(value);
    }

    @Unique
    private static MutableComponent ae2addon$infiniteTooltipValue() {
        return Component.literal(AE2ADDON_INFINITE_TEXT)
                .withStyle(Tooltips.NUMBER_TEXT);
    }
}
