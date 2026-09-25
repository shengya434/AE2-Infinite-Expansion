package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * "带编码槽的界面"这一小撮共同点的接口（2026-09-21 v249）。
 * <p>
 * 为什么需要它：编码链路（{@code QianJiPatternPacket}）原本只在 AE2 自己的
 * {@code AEBaseMenu} 上找 {@code SlotSemantics.BLANK_PATTERN} / {@code ENCODED_PATTERN}。
 * 我们自己的菜单（如图案终端）继承的是原版 {@code AbstractContainerMenu}，
 * 没有那套槽语义 —— 于是这里约定一个最小接口，让编码链路也能认我们的界面：
 * <ul>
 *   <li>扣空白样板：优先用 {@link #blankSlots()} 里的槽；</li>
 *   <li>放成品：用 {@link #encodedSlot()}（**只能放千机样板**，别的会被 mayPlace 拒掉）。</li>
 * </ul>
 * 这样"终端里缝两个槽"就真的接上了现有编码流程，不用另写一套。
 */
public interface QianJiEncodedSlotHolder {

    /** 空白样板槽（可以给多个；编码时按顺序找第一个有空白样板的） */
    List<Slot> blankSlots();

    /** 编码样板槽（成品放这里；可为 null = 这个界面没编码槽） */
    @Nullable Slot encodedSlot();

    /**
     * 这个界面所属的网络（服务端才有；客户端返回 null）。
     * 终端的全网列表/搜索都靠它拿 {@code IGrid}。
     */
    @Nullable IGrid grid();
}
