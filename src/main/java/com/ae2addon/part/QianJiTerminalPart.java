package com.ae2addon.part;

import appeng.api.networking.IGrid;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.items.parts.PartModels;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import com.ae2addon.gui.QianJiTerminalHandlers;
import com.ae2addon.gui.QianJiTerminalHost;
import com.ae2addon.gui.QianJiTerminalMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 千机·样板终端（线缆面板形态，2026-09-21 v249）。
 * <p>
 * 它把两件事缝在一起：
 * <ol>
 *   <li>**管理**：把**整张网络上所有千机的样板**拉成一个不分页的长列表（滑条 + 搜索）——数据来自
 *       {@code QianJiNetworkPatterns}（v248），复用 AE2 的 {@code IGrid#getMachines} 链路。</li>
 *   <li>**编码**：界面里只放**空白样板槽**与**编码样板槽**两个槽（一上一下、中间一个灰色向下箭头）。
 *       编码入口沿用现有的 JEI「编码」路径（{@code QianJiPatternPacket}）——只要菜单用 AE2 的
 *       {@code SlotSemantics.BLANK_PATTERN} / {@code ENCODED_PATTERN} 声明这两个槽，它就会自动优先
 *       扣终端槽里的空白样板、并把编好的**千机样板**放进编码槽。</li>
 * </ol>
 * 形状与关闭行为照我们自己的 {@code InfiniteInterfacePart}（同为线缆面板 part）。
 * <p>
 * 2026-09-21 v272：槽位与规则抽到 {@link QianJiTerminalHost} / {@link QianJiTerminalHandlers}，
 * 好让**无线终端**（{@code QianJiWirelessTerminalItem}）复用同一张菜单与同一个界面。
 */
public class QianJiTerminalPart extends AEBasePart implements QianJiTerminalHost {

    /** part 模型：{@code assets/ae2addon/models/part/qianji_terminal.json} */
    private static final ResourceLocation MODEL_BASE =
            new ResourceLocation("ae2addon", "part/qianji_terminal");

    @PartModels
    public static final IPartModel MODELS = new PartModel(MODEL_BASE);

    /**
     * 空白样板槽 / 编码样板槽的库存（各 1 格）。
     * <p>
     * **为什么放在 part 上而不是菜单里**：菜单是"每次打开都新建"的临时对象，
     * 槽里的东西必须跟着终端本体走（玩家走开再回来还在），所以库存挂在 part 上并写进 NBT。
     * 槽位过滤（只收空白样板 / 只收千机样板）由 {@link QianJiTerminalHandlers} 统一负责。
     */
    private final ItemStackHandler blankPatterns = QianJiTerminalHandlers.blankHandler(() -> {
        savePart();
        // 空白样板槽 = 网络里空白样板的"映射"（v282）：放进来就送进网络，槽里不留东西。
        // ⚠ 字段初始化式里引用自身必须写限定名（简单名 = 编译期自引用错误）
        QianJiTerminalHandlers.flushBlankToNetwork(
                getTerminalGrid(), this.blankPatterns, this::savePart);
    });

    /**
     * 最后一个打开过这个终端的玩家（v277）。
     * <p>
     * 用途只有一个：编码槽自动推入千机成功后，要通知**正在看这个终端界面的人**
     * 把列表跳过去并高亮那个栏位。part 自己不认识玩家，所以在打开界面时记一笔
     * （null / 已经关掉界面都不影响，发通知前会再检查他是不是还开着终端菜单）。
     */
    @Nullable
    private Player lastViewer;

    private final ItemStackHandler encodedPatterns =
            QianJiTerminalHandlers.encodedHandler(() -> {
                savePart();
                // sensei：**编码槽的样板自动推入千机** —— 编好的千机样板一落进这个槽就自动塞进
                // 一台有空位的千机（槽随即清空）；全网络都满了就留在槽里等玩家处理。
                // ⚠ 这里必须写 this.encodedPatterns：字段初始化式里用**简单名**引用自己
                // 是编译期错误（自引用），限定名才是合法的"稍后读取"。
                QianJiTerminalHandlers.autoPushEncoded(
                        getTerminalGrid(), this.encodedPatterns, this::savePart, this.lastViewer);
            });

    public QianJiTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    // ── QianJiTerminalHost ──

    @Override
    public @Nullable IGrid getTerminalGrid() {
        try {
            var node = getMainNode();
            return node == null ? null : node.getGrid();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public ItemStackHandler blankInv() {
        return blankPatterns;
    }

    @Override
    public ItemStackHandler encodedInv() {
        return encodedPatterns;
    }

    /** 内容变了就通知主机落盘（照 {@code InfiniteInterfacePart} 的写法：getHost().markForSave()） */
    @Override
    public void saveHost() {
        savePart();
    }

    /** 空白样板槽库存（旧访问器，保留给老调用点） */
    public ItemStackHandler getBlankPatternInventory() {
        return blankPatterns;
    }

    /** 编码样板槽库存（旧访问器，保留给老调用点） */
    public ItemStackHandler getEncodedPatternInventory() {
        return encodedPatterns;
    }

    private void savePart() {
        try {
            if (getHost() != null) {
                getHost().markForSave();
            }
        } catch (Throwable ignored) {
            // 主机还没挂上/正被拆除时忽略：下一次保存会带上
        }
    }

    /**
     * 面板形态的碰撞箱：一块贴着线缆的薄板 + 中间凸起（与 AE2 终端的手感一致）。
     * 尺寸先按面板套路取，观感不合适再调——不影响功能。
     */
    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public IPartModel getStaticModels() {
        return MODELS;
    }

    @Override
    public boolean onPartActivate(Player p, InteractionHand hand, Vec3 pos) {
        if (p.getCommandSenderWorld().isClientSide()) {
            return true;   // 客户端不做事，等服务端的菜单包
        }
        if (p instanceof ServerPlayer sp) {
            // 记一笔"最后一个看这个终端的人"：编码槽自动推入成功后要通知他跳转+高亮（v277）
            this.lastViewer = p;
            NetworkHooks.openScreen(sp, new SimpleMenuProvider(
                    (containerId, inventory, ignored) ->
                            new QianJiTerminalMenu(containerId, inventory, this),
                    Component.translatable("gui.ae2addon.qianji_terminal.title")),
                    // 面板形态的打开数据：不是通用终端、没有升级卡槽、没有奇点槽（2026-09-21 v273/v274）
                    QianJiTerminalMenu::writeOpenDataForPlainForm);
        }
        return true;
    }

    // ── 存档 ──

    @Override
    public void readFromNBT(net.minecraft.nbt.CompoundTag data) {
        super.readFromNBT(data);
        if (data.contains("blankPatterns")) {
            blankPatterns.deserializeNBT(data.getCompound("blankPatterns"));
        }
        if (data.contains("encodedPatterns")) {
            encodedPatterns.deserializeNBT(data.getCompound("encodedPatterns"));
        }
    }

    @Override
    public void writeToNBT(net.minecraft.nbt.CompoundTag data) {
        super.writeToNBT(data);
        data.put("blankPatterns", blankPatterns.serializeNBT());
        data.put("encodedPatterns", encodedPatterns.serializeNBT());
    }
}
