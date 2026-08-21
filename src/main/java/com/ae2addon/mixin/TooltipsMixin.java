package com.ae2addon.mixin;

import appeng.core.localization.Tooltips;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 AE2 Tooltips.getByteAmount() 对 TB/PB/EB 级字节数值的越界问题。
 * <p>
 * 原版 BYTE_NUMS 数组：[1024, 1048576, 1073741824, 1073741824]
 * 第 3、4 项均为 GB，缺少 TB(1099511627776)/PB(1125899906842624)/EB(1152921504606846976)。
 * 当数值 ≥ 1TB 时，循环无法在 4 元素内终止，i 递增至 4 后访问 BYTE_NUMS[4] 越界。
 * <p>
 * 本 Mixin 覆写 getByteAmount，使用 6 级二进制单位，与现有 units 数组的
 * k(0)/M(1)/G(2)/T(3)/P(4)/E(5) 一一对应。
 */
@Mixin(Tooltips.class)
public abstract class TooltipsMixin {

    @Shadow(remap = false)
    private static String[] units;

    @Shadow(remap = false)
    private static String getAmount(double value, long divisor) {
        throw new AssertionError("Shadow method not implemented");
    }

    /**
     * 注入 getByteAmount：在原方法返回前，用正确的 6 级二进制单位覆盖返回值。
     * 原版 BYTE_NUMS 数组只有 4 个元素，TB/PB/EB 缺失导致 ≥1TB 时数组越界。
     */
    @Inject(method = "getByteAmount", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void onGetByteAmount(long value, CallbackInfoReturnable<Tooltips.Amount> cir) {
        // 无限存储哨兵：直接显示 ∞（集成 CPU 的 getStorageBytes 返回 Long.MAX_VALUE）
        if (value == Long.MAX_VALUE) {
            cir.setReturnValue(new Tooltips.Amount("∞", ""));
            return;
        }

        final long[] BYTE_THRESHOLDS = {
                1024L,                    // 0: KB (2^10)
                1048576L,                 // 1: MB (2^20)
                1073741824L,              // 2: GB (2^30)
                1099511627776L,           // 3: TB (2^40)
                1125899906842624L,        // 4: PB (2^50)
                1152921504606846976L      // 5: EB (2^60)
        };

        if (value < BYTE_THRESHOLDS[0]) {
            cir.setReturnValue(new Tooltips.Amount(Long.toString(value), ""));
            return;
        }

        for (int i = 0; i < BYTE_THRESHOLDS.length; i++) {
            if (value / BYTE_THRESHOLDS[i] < 1000) {
                cir.setReturnValue(new Tooltips.Amount(
                        getAmount((double) value, BYTE_THRESHOLDS[i]),
                        units[i]
                ));
                return;
            }
        }

        int last = BYTE_THRESHOLDS.length - 1;
        cir.setReturnValue(new Tooltips.Amount(
                getAmount((double) value, BYTE_THRESHOLDS[last]),
                units[last]
        ));
    }
}
