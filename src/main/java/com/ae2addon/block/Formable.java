package com.ae2addon.block;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

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

    /**
     * 创造模式「已成型」变体放置时调用：默认等价于 {@link #setFormed(boolean)}，
     * 实现方可在此补足「完整成型」所需的附加状态（如集成型CPU 的并行处理器标志、
     * 多方块朝向按玩家放置方向置位）。
     *
     * @param player 放置者（可能为 null，如存档回放/指令放置）
     */
    default void applyCreativeFormed(@Nullable Player player) {
        applyCreativeFormed();
    }

    /**
     * 创造模式「已成型」变体放置时调用：默认等价于 {@link #setFormed(boolean)}。
     */
    default void applyCreativeFormed() {
        setFormed(true);
    }
}
