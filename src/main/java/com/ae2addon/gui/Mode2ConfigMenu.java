package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.init.ModMenuTypes;
import com.ae2addon.network.Mode2ConfigPacket;
import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 模式2配置界面 — 点击背包物品添加到白名单 + 存储面板
 * 背包槽位下移，为面板腾出空间
 */
public class Mode2ConfigMenu extends AbstractContainerMenu {

    private final ItemStack cellStack;
    private final InfiniteDriveBE drive;
    private final int driveSlot;
    /** 客户端缓存的面板数据 */
    private List<UnlimitedCellInventory.PanelItem> panelItems = new ArrayList<>();
    /** 客户端缓存的 tag 规则 */
    private List<String> tagRules = new ArrayList<>();
    /** 客户端缓存的 mod 规则 */
    private List<String> modRules = new ArrayList<>();
    /** 客户端缓存的每条 tag/mod 规则模式 */
    private final Map<String, Integer> ruleModes = new HashMap<>();
    /** 客户端缓存的黑名单 AEKey */
    private List<AEKey> blacklist = new ArrayList<>();
    /** 由屏幕控制：是否隐藏物品栏 */
    public boolean slotsHidden = false;

    public Mode2ConfigMenu(int id, Inventory playerInventory, ItemStack cellStack) {
        this(id, playerInventory, cellStack, null, -1);
    }

    public Mode2ConfigMenu(int id, Inventory playerInventory, ItemStack cellStack,
                           InfiniteDriveBE drive, int driveSlot) {
        super(ModMenuTypes.MODE2_CONFIG.get(), id);
        this.cellStack = cellStack;
        this.drive = drive;
        this.driveSlot = driveSlot;

        // 使用可切换隐藏的 Slot 包装
        var inv = playerInventory;
        // 玩家背包（9x3）
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++) {
                int idx = c + r * 9 + 9;
                addSlot(new Slot(inv, idx, 48 + c * 18, 192 + r * 18) {
                    @Override public boolean isActive() { return !slotsHidden; }
                });
            }
        // 快捷栏（1x9）
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 48 + c * 18, 250) {
                @Override public boolean isActive() { return !slotsHidden; }
            });
        }
    }

    public static Mode2ConfigMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new Mode2ConfigMenu(id, inv, buf.readItem());
    }

    public ItemStack getCellStack() { return cellStack; }

    /** 编辑器打开期间确认目标仍在原槽，避免改到已取出的旧栈。 */
    public boolean isCellPresent(Player player) {
        return drive == null || (driveSlot >= 0 && drive.isFormed()
                && player.level().hasChunkAt(drive.getBlockPos())
                && player.level().getBlockEntity(drive.getBlockPos()) == drive
                && drive.getInternalInventory().getStackInSlot(driveSlot) == cellStack);
    }

    /** 活栈的 NBT 已修改，通知驱动器保存。 */
    public void markCellChanged() {
        if (drive != null) drive.setChanged();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (drive != null && !player.level().isClientSide && isCellPresent(player)) {
            drive.onChangeInventory(drive.getInternalInventory(), driveSlot);
        }
    }

    public long getThreshold() {
        return cellStack.getOrCreateTag().getLong("thr");
    }

    public int getWorkMode() {
        int wm = cellStack.getOrCreateTag().getInt("wm");
        if (wm < 1 || wm > 3) wm = 1;
        return wm;
    }

    /** 请求服务端发送面板数据 */
    public void requestPanelData() {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(3));
    }

    /** 客户端更新面板数据（从网络包接收） */
    public void setPanelItems(List<UnlimitedCellInventory.PanelItem> items) {
        this.panelItems = items;
    }

    /** 客户端获取面板数据 */
    public List<UnlimitedCellInventory.PanelItem> getPanelItems() {
        return panelItems;
    }

    public long getItemThreshold(AEKey key) {
        for (var item : panelItems) {
            if (item.key.equals(key)) return item.itemThreshold;
        }
        return 0L;
    }

    public void sendSetItemThreshold(CompoundTag keyTag, long value) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(keyTag, value));
    }

    public void sendSetQuantity(CompoundTag keyTag, String quantityText, boolean lock) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(keyTag, quantityText, lock));
    }

    public UnlimitedCellInventory.PanelItem getPanelItem(AEKey key) {
        for (var item : panelItems) {
            if (item.key.equals(key)) return item;
        }
        return null;
    }

    /** 客户端更新规则数据 */
    public void setRuleData(boolean isTag, List<String> rules, List<Integer> modes) {
        String prefix = isTag ? "tag:" : "mod:";
        ruleModes.keySet().removeIf(key -> key.startsWith(prefix));
        for (int i = 0; i < rules.size(); i++) {
            ruleModes.put(prefix + rules.get(i), i < modes.size() && modes.get(i) == 2 ? 2 : 1);
        }
        if (isTag) {
            tagRules = new ArrayList<>(rules);
        } else {
            modRules = new ArrayList<>(rules);
        }
    }

    public List<String> getTagRules() {
        return tagRules;
    }

    public List<String> getModRules() {
        return modRules;
    }

    public int getRuleMode(String ruleKey) {
        return ruleModes.getOrDefault(ruleKey, 1);
    }

    /** 添加 tag 规则 */
    public void sendAddTagRule(String tag) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(7, tag, true));
    }

    /** 移除 tag 规则 */
    public void sendRemoveTagRule(String tag) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(8, tag, true));
    }

    /** 添加 mod 规则 */
    public void sendAddModRule(String mod) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(7, mod, false));
    }

    /** 移除 mod 规则 */
    public void sendRemoveModRule(String mod) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(8, mod, false));
    }

    /** 设置单条规则的无限模式。 */
    public void sendSetRuleMode(String rule, boolean isTag, int mode) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(15, rule, isTag, mode));
    }

    /** 客户端更新黑名单缓存 */
    public void setBlacklist(List<AEKey> keys) {
        this.blacklist = new ArrayList<>(keys);
    }

    /** 客户端获取黑名单 */
    public List<AEKey> getBlacklist() {
        return blacklist;
    }

    /** 切换黑名单（传完整 AEKey NBT） */
    public void sendToggleBlacklist(CompoundTag keyTag) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(keyTag, false));
    }

    /** 切换无限状态：发送完整 AEKey NBT 到服务端 */
    public void sendToggleInfinite(CompoundTag keyTag) {
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(keyTag));
    }

    /** 点击背包物品 → 加入白名单（自动检测流体容器） */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = getSlot(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack clicked = slot.getItem();

        // 发送完整 ItemStack（含 NBT，以便服务端检测流体等内部存储）
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(clicked.copy()));
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) { return !cellStack.isEmpty() && isCellPresent(p); }
}
