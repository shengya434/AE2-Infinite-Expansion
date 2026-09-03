package com.ae2addon.mixin;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.core.localization.Tooltips;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.ae2addon.AE2Addon;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CPU 选择列表显示美化：集成 CPU 的「无限存储 / 拉满并行」渲染成 ∞。
 * <p>
 * 哨兵值：
 * - 存储：Long.MAX_VALUE（IntegratedCPUBE.getStorageBytes / getAvailableStorage）
 * - 并行：Integer.MAX_VALUE−1（getCoProcessors，AE2 内部 +1 不溢出）
 * <p>
 * 2026-09-03 sensei：∞ 处读 config cpuDisplayBytes/cpuDisplayThreads——
 * 填了具体值就显示具体值（默认 MAX / 0 保持 ∞）。
 * 思路来自 OmniSequence-Transfinite 的 CPUSelectionListMixin。
 */
@Mixin(value = CPUSelectionList.class, remap = false)
public abstract class CPUSelectionListMixin {

    @Unique
    private static final String AE2ADDON_INFINITE_TEXT = "∞";

    /** 存储显示值：config 填具体值 → 该值；仍为 MAX → ∞（保持无限语义）。 */
    @Unique
    private static long ae2addon$displayBytes() {
        long v = com.ae2addon.config.AE2AddonConfig.cpuDisplayBytes();
        return v <= 0 || v == Long.MAX_VALUE ? Long.MAX_VALUE : v;
    }

    /** 并行显示值：config 0（拉满）→ MAX-1 哨兵；填 N → N。 */
    @Unique
    private static int ae2addon$displayThreads() {
        return com.ae2addon.config.AE2AddonConfig.cpuDisplayThreads();
    }

    @Inject(method = "formatStorage", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2addon$formatInfiniteStorage(
            CraftingStatusMenu.CraftingCpuListEntry cpu,
            CallbackInfoReturnable<String> callback) {
        if (cpu.storage() == Long.MAX_VALUE) {
            long disp = ae2addon$displayBytes();
            if (disp == Long.MAX_VALUE) {
                callback.setReturnValue(AE2ADDON_INFINITE_TEXT);
            } else {
                callback.setReturnValue(appeng.core.localization.Tooltips.ofBytes(disp).getString());
            }
        }
    }

    @Redirect(method = "drawBackgroundLayer",
            at = @At(value = "INVOKE",
                    target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;"),
            require = 0)
    private String ae2addon$formatInfiniteParallelism(int value) {
        if (value != Integer.MAX_VALUE - 1) {
            return String.valueOf(value);
        }
        int disp = ae2addon$displayThreads();
        return disp == Integer.MAX_VALUE - 1 ? AE2ADDON_INFINITE_TEXT : String.valueOf(disp);
    }

    @Redirect(method = "getTooltip",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;ofNumber(J)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent ae2addon$tooltipInfiniteParallelism(long value) {
        if (value != Integer.MAX_VALUE - 1) {
            return Tooltips.ofNumber(value);
        }
        int disp = ae2addon$displayThreads();
        return disp == Integer.MAX_VALUE - 1
                ? ae2addon$infiniteTooltipValue()
                : Tooltips.ofNumber(disp);
    }

    @Redirect(method = "getTooltip",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;ofBytes(J)"
                            + "Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent ae2addon$tooltipInfiniteStorage(long value) {
        if (value != Long.MAX_VALUE) {
            return Tooltips.ofBytes(value);
        }
        long disp = ae2addon$displayBytes();
        return disp == Long.MAX_VALUE
                ? ae2addon$infiniteTooltipValue()
                : Tooltips.ofBytes(disp);
    }

    @Unique
    private static MutableComponent ae2addon$infiniteTooltipValue() {
        return Component.literal(AE2ADDON_INFINITE_TEXT)
                .withStyle(Tooltips.NUMBER_TEXT);
    }
}
