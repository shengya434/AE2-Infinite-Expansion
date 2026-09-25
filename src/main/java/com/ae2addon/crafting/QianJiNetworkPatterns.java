package com.ae2addon.crafting;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import com.ae2addon.block.QianJiBE;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 网络级千机样板枚举 / 搜索（2026-09-21 v248）。
 * <p>
 * 用途：给"线缆面板终端 / 无线终端"（把样板管理终端与编码终端缝在一起的那个）提供**全网视角**——
 * 不再局限于某一台千机的 1280 槽，而是"这张网络上的所有千机、所有样板槽"。
 * <p>
 * **为什么不需要改 AE2**：{@code QianJiBE} 已经实现了 AE2 的
 * {@link PatternContainer}，而 AE2 的 {@code IGrid#getMachines(Class)} 正是
 * 样板管理终端枚举"网络里有哪些样板容器"的入口 —— 我们直接复用这条链路。
 * <p>
 * 三个刻意的设计：
 * <ol>
 *   <li>**结果顺序稳定**：{@code getMachines} 返回的是 {@code Set}（顺序不保证），
 *       所以这里按「千机坐标 → 槽位号」显式排序 —— 否则同一个列表每次打开顺序都在跳。</li>
 *   <li>**不带 tooltip**：全网可能有几千条，每条都拼 tooltip 会让包和内存都很难看。
 *       列表只出"标签"，tooltip 由客户端**按需**单独要（见后续终端版本）。</li>
 *   <li>**不改动千机自己的搜索**：{@code QianJiBE#searchPatterns} 保持不变（单机视角），
 *       这里只是把它套到"多台千机"上。</li>
 * </ol>
 */
public final class QianJiNetworkPatterns {

    private QianJiNetworkPatterns() {}

    /**
     * 一条跨机命中。
     *
     * @param pos  样板所在千机的坐标（终端要显示"哪台千机"，这是与单机搜索的关键区别）
     * @param slot 该千机内的样板槽位号（0..1279）
     * @param data 样板数据
     */
    public record Entry(BlockPos pos, int slot, QianJiPatternData data) {

        /** 供 UI 用的定位串：{@code [x, y, z] 第 N 页 槽 i}（页 = slot/54，与千机 GUI 分页一致） */
        public String where() {
            return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"
                    + " 第 " + (slot / 54 + 1) + " 页 槽 " + slot;
        }
    }

    /** 千机排序：坐标字典序（先 X、再 Y、再 Z），保证列表顺序稳定 */
    private static final Comparator<QianJiBE> BY_POS = Comparator
            .comparingInt((QianJiBE be) -> be.getBlockPos().getX())
            .thenComparingInt(be -> be.getBlockPos().getY())
            .thenComparingInt(be -> be.getBlockPos().getZ());

    /**
     * 收集这张网络上**所有千机**的全部非空样板槽。
     *
     * @param grid 目标网络（{@code null} = 没接入网络 → 返回空表）
     * @return 按「千机坐标 → 槽位号」排序的条目列表
     */
    public static List<Entry> collect(@Nullable IGrid grid) {
        var out = new ArrayList<Entry>();
        if (grid == null) return out;

        var qianjis = new ArrayList<QianJiBE>();
        for (PatternContainer container : grid.getMachines(PatternContainer.class)) {
            // 只收我们自己的千机：样板的有意义文本（searchText）只有 QianJiPatternData 有；
            // AE2 原生样板供应器里的样板以后要纳入的话，得另配一套文本来源。
            if (container instanceof QianJiBE qianji) qianjis.add(qianji);
        }
        qianjis.sort(BY_POS);

        for (QianJiBE qianji : qianjis) {
            var handler = qianji.getPatternHandler();
            BlockPos pos = qianji.getBlockPos();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                var data = QianJiPatternData.of(stack);
                if (data == null || data.isEmpty()) continue;
                out.add(new Entry(pos, slot, data));
            }
        }
        return out;
    }

    /** 网络上有多少张可识别的千机样板（= {@link #collect} 的大小，单独给命令/UI 报数用） */
    public static int count(@Nullable IGrid grid) {
        return collect(grid).size();
    }

    /**
     * 在**全网**样板里搜关键词（规则与单机搜索完全一致：小写子串包含，见
     * {@link QianJiPatternData#searchText()}）。
     *
     * @param grid  目标网络
     * @param term  关键词；空白 → 空表
     * @param limit 条数上限；{@code <=0} = 不限
     * @return 命中条目（保持「千机坐标 → 槽位号」顺序）
     */
    public static List<Entry> search(@Nullable IGrid grid, String term, int limit) {
        var hits = new ArrayList<Entry>();
        String needle = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return hits;
        for (Entry entry : collect(grid)) {
            if (entry.data().searchText().contains(needle)) {
                hits.add(entry);
                if (limit > 0 && hits.size() >= limit) break;
            }
        }
        return hits;
    }

    /**
     * 取一段（给"不分页、只滑条"的列表用）。
     * <p>
     * 终端会把全网条目拉成一个长列表，**一次性发几千条不现实**，所以按窗口切片取
     * （客户端滚到哪取到哪）。这里只做纯切片，不管缓存 —— 缓存策略留给终端那一层定。
     *
     * @param grid   目标网络
     * @param offset 起始下标（&lt;0 视为 0）
     * @param limit  取多少条（&lt;=0 视为 0）
     * @return 切片（越界时返回空表或短表，不抛异常）
     */
    public static List<Entry> slice(@Nullable IGrid grid, int offset, int limit) {
        if (limit <= 0) return List.of();
        var all = collect(grid);
        int from = Math.max(0, offset);
        if (from >= all.size()) return List.of();
        int to = (int) Math.min((long) all.size(), (long) from + limit);
        return all.subList(from, to);
    }
}
