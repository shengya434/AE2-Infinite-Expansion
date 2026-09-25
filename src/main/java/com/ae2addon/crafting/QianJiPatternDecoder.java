package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 让**千机样板**在 AE2 眼里成为"真正的样板"（2026-09-20）。
 * <p>
 * <b>为什么需要它</b>：{@code QianJiPatternItem extends Item}（普通物品），AE2 的
 * {@code PatternDetailsHelper.isEncodedPattern(...)} / {@code decodePattern(...)} 对它一律返回空 ——
 * 于是**所有基于"这是不是一张样板"的外部功能都不认千机样板**：
 * EAEP 的「上传样板到矩阵/供应器」、样板管理终端的一些判断、其它 mod 的样板工具……全都把我们当"普通物品"。
 * 之前逐个 mixin 去放宽它们的过滤，属于治标；这里用一个**解码器**从根上解决。
 * <p>
 * AE2 为此提供了公开 API：{@code PatternDetailsHelper.registerDecoder(...)}（见 {@code AE2Addon#onCommonSetup}）。
 * 注册之后，{@code isEncodedPattern} 对千机样板返回真、{@code decodePattern} 返回我们的
 * {@link QianJiPatternDetails} —— 千机样板就成了 AE2 生态里的"一等样板"。
 * <p>
 * ⚠ 只认我们自己的物品（{@code ae2addon:qianji_pattern} 且 NBT 里有样板数据），
 * 其它物品一律返回空/假，保证**不影响 AE2 自己的样板**与别的 mod。
 */
public class QianJiPatternDecoder implements IPatternDetailsDecoder {

    /**
     * 一次性取证（2026-09-20 sensei：样板管理终端里按产物搜不到千机样板）：
     * 这一步能判定"样板管理终端是不是靠解码样板内容来搜索" ——
     * 若他搜索时这条日志出现，说明搜索确实走解码（那问题在搜索文本的构造）；
     * 若从不出现，说明它压根不解码（要去查客户端 screen 层）。
     */
    private static boolean loggedOnce = false;

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() == ModItems.QIAN_JI_PATTERN.get()
                && QianJiPatternData.of(stack) != null;
    }

    @Override
    @Nullable
    public IPatternDetails decodePattern(AEItemKey key, Level level) {
        if (key == null || key.getItem() != ModItems.QIAN_JI_PATTERN.get()) return null;
        // AEItemKey → ItemStack（数量 1 就够：样板数据全在 NBT 上）
        return decodePattern(key.toStack(1), level, false);
    }

    @Override
    @Nullable
    public IPatternDetails decodePattern(ItemStack stack, Level level, boolean tryRecover) {
        if (stack.isEmpty() || stack.getItem() != ModItems.QIAN_JI_PATTERN.get()) return null;
        QianJiPatternData data = QianJiPatternData.of(stack);
        if (data == null) return null;
        // 空数据（既没输入也没产出）= 废样板，别交给 AE2 当有效样板用
        if (data.inputs().isEmpty() && data.primary().isEmpty() && data.chanced().isEmpty()) return null;
        if (!loggedOnce) {
            loggedOnce = true;
            // 只打前 5 层调用栈的"类.方法"：足以判定是"样板管理终端搜索"在解码，
            // 还是别的路径（例如千机向网络暴露样板时）顺手解码的 —— 两者的处置完全不同。
            var sb = new StringBuilder();
            try {
                var st = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(st.length, 8); i++) {
                    String cn = st[i].getClassName();
                    sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(" < ");
                }
            } catch (Throwable ignored) {
                sb.append("(栈取不到)");
            }
            com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 千机样板解码器**被调用**，调用来源：{}", sb);
        }
        return new QianJiPatternDetails(data, stack.copy());
    }
}
