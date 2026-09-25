package com.ae2addon.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2addon.config.AE2AddonConfig;
import com.ae2addon.item.BoundInfiniteCellItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 无限 xxx 元件的存储实现：**单 key 无限**。
 * <p>
 * 与 {@link UnlimitedCellInventory}（三模式万能）不同：
 * <ul>
 *   <li>只认 NBT 绑定的那一个 key；其它 key 既不报有货也不收</li>
 *   <li>该 key **无限存取**：提取永远给得出、存入多少都收下</li>
 *   <li>无状态、无需持久化（"存量"是无限的，没有需要记住的数字）</li>
 * </ul>
 * 设计文档：{@code docs/infinite-essence-cell.md}。由 {@code UnlimitedCellHandler} 创建。
 */
public class BoundInfiniteCellInventory implements StorageCell {

    private final ItemStack cellItem;
    /** 绑定的 key（null = NBT 损坏/未绑定，此时表现成"空元件"） */
    private final AEKey bound;

    public BoundInfiniteCellInventory(ItemStack cellItem, ISaveProvider saveProvider) {
        this.cellItem = cellItem;
        this.bound = BoundInfiniteCellItem.getBoundKey(cellItem);
    }

    public AEKey getBoundKey() {
        return bound;
    }

    /** 无限哨兵量（config infiniteItemAmount 热加载，默认 Long.MAX） */
    private static long infinite() {
        return Math.max(1L, AE2AddonConfig.infiniteItemAmount());
    }

    private boolean matches(AEKey what) {
        return what != null && bound != null && bound.equals(what);
    }

    /** 绑定 key：全额收下（返回 amount）。其它 key：拒收（返回 0）。 */
    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
        if (what == null || amount <= 0) return 0;
        return matches(what) ? amount : 0;
    }

    /** 绑定 key：要多少给多少（无限）。其它 key：0。 */
    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource src) {
        if (what == null || amount <= 0) return 0;
        return matches(what) ? amount : 0;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (out == null || bound == null) return;
        // ⚠ set(最大值)而不是 add：多个无限元件/无限盘聚合同一个 KeyCounter 时，
        // add(Long.MAX) 会溢出成负数 → 模拟看到负库存 → 提取失败（与万能元件同一坑）
        if (out.get(bound) < infinite()) {
            out.set(bound, infinite());
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource src) {
        // 只有绑定的那个 key 才宣称"首选"，别的物品留给网络里其它存储
        return matches(what);
    }

    @Override
    public Component getDescription() {
        return cellItem.getHoverName();
    }

    @Override
    public CellState getStatus() {
        // 未绑定（NBT 损坏）= 视为空元件；已绑定 = 有内容
        return bound == null ? CellState.ABSENT : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 0;
    }

    @Override
    public boolean canFitInsideCell() {
        return false;
    }

    @Override
    public void persist() {
        // 无状态：不需要写盘
    }
}
