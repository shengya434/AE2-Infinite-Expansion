package com.ae2addon.mixin;

import appeng.core.localization.Tooltips;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

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

    @Shadow
    private static String[] units;

    @Shadow
    private static String getAmount(double value, long divisor) {
        throw new AssertionError("Shadow method not implemented");
    }

    /**
     * 覆写 getByteAmount 以正确支持 KB ~ EB 级别的字节格式化。
     */
    @Overwrite(remap = false)
    public static Tooltips.Amount getByteAmount(long value) {
        // 二进制字节单位阈值，与原 units 数组索引对应
        final long[] BYTE_THRESHOLDS = {
                1024L,                    // 0: KB (2^10)  → units[0] = "k"
                1048576L,                 // 1: MB (2^20)  → units[1] = "M"
                1073741824L,              // 2: GB (2^30)  → units[2] = "G"
                1099511627776L,           // 3: TB (2^40)  → units[3] = "T"
                1125899906842624L,        // 4: PB (2^50)  → units[4] = "P"
                1152921504606846976L      // 5: EB (2^60)  → units[5] = "E"
        };

        // 原始字节（< 1024）：直接显示数值，无后缀
        if (value < BYTE_THRESHOLDS[0]) {
            return new Tooltips.Amount(Long.toString(value), "");
        }

        // 遍历阈值表，找到使 value / BYTES[i] < 1000 的最合适单位
        for (int i = 0; i < BYTE_THRESHOLDS.length; i++) {
            if (value / BYTE_THRESHOLDS[i] < 1000) {
                return new Tooltips.Amount(
                        getAmount((double) value, BYTE_THRESHOLDS[i]),
                        units[i]
                );
            }
        }

        // 极值兜底（例如 Long.MAX_VALUE）
        int last = BYTE_THRESHOLDS.length - 1;
        return new Tooltips.Amount(
                getAmount((double) value, BYTE_THRESHOLDS[last]),
                units[last]
        );
    }
}
