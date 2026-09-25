package com.ae2addon.compat.ae2wtlib;

import de.mari_023.ae2wtlib.networking.ClientNetworkManager;
import de.mari_023.ae2wtlib.networking.c2s.CycleTerminalPacket;
import de.mari_023.ae2wtlib.wut.CycleTerminalButton;
import net.minecraft.client.gui.components.Button;

/**
 * 通用终端状态下那个**「打开下一个终端」**按钮（2026-09-21 v273）。
 * <p>
 * 按钮按下 = 给服务端发 {@code CycleTerminalPacket}，由 AE2WTLib 按
 * {@code terminalNames} 的顺序切到下一个**已装**状态 —— 从别的终端切到千机是这个按钮，
 * 从千机再点一次就切回去（循环）。
 * <p>
 * ⚠ **方向别写反（2026-09-22 v275 修正）**：{@code WUTHandler.cycle(player, locator, stack, boolean)}
 * 里 **{@code true} = 倒着切（上一个）**，{@code false} = 顺着切（下一个）——
 * 字节码里 `if (flag) idx = indexOf(current) - 1 else idx = indexOf(current) + 1`。
 * v274 我发了 {@code true}，于是和 AE2WTLib 自己终端上的按钮**方向相反**，
 * 两个状态之间来回弹（sensei 实测：按一下切到上一个，再按又切回来 = "死循环"）。
 * AE2WTLib 自己的按钮发的是 {@code IUniversalTerminalCapable#isHandlingRightClick()}
 * （普通情况就是 false），这里照它来。
 * <p>
 * 只负责"造按钮"：位置与 {@code addRenderableWidget} 交给调用方
 * （{@code Screen#addRenderableWidget} 是 protected，外部类调不了）。
 * <p>
 * ⚠ 客户端专用，且只在 AE2WTLib 存在时才会被调用。
 */
public final class AE2WTLibClientCompat {

    private AE2WTLibClientCompat() {
    }

    /** 造一个"下一个终端"按钮（16×16，位置由调用方设） */
    public static Button createCycleButton() {
        return new CycleTerminalButton(
                b -> ClientNetworkManager.sendToServer(new CycleTerminalPacket(false)));
    }
}
