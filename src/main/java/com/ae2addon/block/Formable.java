package com.ae2addon.block;

/**
 * 「可成型」多方块控制器方块实体的统一接口。
 * <p>
 * 本体实现为木棍右键检测结构后置位；创造模式「已成型」变体物品
 * （{@link com.ae2addon.item.FormedBlockItem}）在放置时直接置位，
 * 免去搭 3×3×3 / 5×5×5 结构。
 * <p>
 * 实现者必须保证 {@code setFormed} 在构造期（{@code level == null}）也能安全调用。
 */
public interface Formable {

    boolean isFormed();

    /**
     * 切换成型状态。实现方负责刷新网络可见性/耗电/缓存等副作用。
     */
    void setFormed(boolean formed);
}
