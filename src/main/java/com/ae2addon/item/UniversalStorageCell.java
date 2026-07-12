package com.ae2addon.item;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.util.ConfigInventory;
import com.ae2addon.gui.ModeSelectMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 万能无限存储元件
 * <p>
 * Mode 1 — 无限制存储   Mode 2 — 自定义无限   Mode 3 — 全类型无限
 */
public class UniversalStorageCell extends Item implements ICellWorkbenchItem {

    public static final int MODE_STANDARD = 1;
    public static final int MODE_CUSTOM = 2;
    public static final int MODE_UNIVERSAL = 3;

    public UniversalStorageCell() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    // ── 右键：模式2→配置界面，其他→模式选择 ──

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        if (player instanceof ServerPlayer serverPlayer) {
            int mode = stack.getOrCreateTag().getInt("umode");
            if (mode < 1 || mode > 3) mode = 1;

            if (mode == MODE_CUSTOM) {
                NetworkHooks.openScreen(serverPlayer, new MenuProvider() {
                    @Override public Component getDisplayName() {
                        return Component.translatable("gui.ae2addon.mode2_config");
                    }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                        return new com.ae2addon.gui.Mode2ConfigMenu(id, inv, stack);
                    }
                }, buf -> {
                    // 只传阈值，裁掉 s2/ul/wl 等重 NBT 数据以防止打开菜单时就炸包
                    ItemStack copy = stack.copy();
                    CompoundTag tag = copy.getOrCreateTag();
                    tag.remove("s1");
                    tag.remove("s2");
                    tag.remove("sa");
                    tag.remove("u");
                    tag.remove("w");
                    buf.writeItem(copy);
                });
            } else {
                // Mode 1 / Mode 3：只传光副本，裁掉存储NBT防炸包
                NetworkHooks.openScreen(serverPlayer, new MenuProvider() {
                    @Override public Component getDisplayName() {
                        return Component.translatable("gui.ae2addon.mode_select");
                    }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                        return new ModeSelectMenu(id, inv, stack);
                    }
                }, buf -> {
                    ItemStack copy = stack.copy();
                    CompoundTag tag = copy.getOrCreateTag();
                    tag.remove("s1");
                    tag.remove("s2");
                    tag.remove("sa");
                    tag.remove("u");
                    tag.remove("w");
                    tag.remove("a");
                    buf.writeItem(copy);
                });
            }
        }
        return InteractionResultHolder.success(stack);
    }

    // ── ICellWorkbenchItem ──

    @Override public boolean isEditable(ItemStack cellItem) { return true; }
    @Override public ConfigInventory getConfigInventory(ItemStack is) {
        return ConfigInventory.configTypes(63, () -> {});
    }
    @Override public IUpgradeInventory getUpgrades(ItemStack cellItem) {
        return UpgradeInventories.forItem(cellItem, 1, (s, u) -> {});
    }
    @Override public FuzzyMode getFuzzyMode(ItemStack is) { return FuzzyMode.IGNORE_ALL; }
    @Override public void setFuzzyMode(ItemStack is, FuzzyMode fzMode) {}

    // ── 工具提示 ──

    @Override
    public Component getName(ItemStack stack) {
        int m = stack.getOrCreateTag().getInt("umode");
        String[] n = {"", "§a无限制", "§e自定义", "§d全类型"};
        if (m < 1 || m > 3) m = 1;
        return Component.literal("§5万能无限 [" + n[m] + "]");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getOrCreateTag();
        int m = tag.getInt("umode");
        if (m < 1 || m > 3) m = 1;

        String[][] info = {
                {},
                {"§a无限制存储", "§7无限容量·全AE类型·正常存取"},
                {"§e自定义无限", "§7白名单无限 + 通用阈值"},
                {"§d全类型无限", "§7仅物品/流体·存入即无限"}
        };
        tooltip.add(Component.literal("模式: " + info[m][0]));
        tooltip.add(Component.literal(info[m][1]));

        // Mode 3 直接显示 ∞
        if (m == 3) {
            tooltip.add(Component.literal("§7字节: §b∞"));
            tooltip.add(Component.literal("§7类型: §b∞"));
            tooltip.add(Component.literal("§7限制: §e仅物品和流体类型"));
            tooltip.add(Component.literal("§7右键切换模式"));
            return;
        }

        // Mode 1 / 2：从统计摘要标签读（轻量，不遍历几千条 NBT）
        long totalBytes = tag.getLong("_b");
        int typeCount = tag.getInt("_t");

        tooltip.add(Component.literal("§7字节: §b" + formatBytes(totalBytes)));
        tooltip.add(Component.literal("§7类型: §b" + typeCount));
        tooltip.add(Component.literal("§7右键切换模式"));
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000_000_000_000L) return String.format("%.1fE", bytes / 1_000_000_000_000_000_000.0);
        if (bytes >= 1_000_000_000_000_000L) return String.format("%.1fP", bytes / 1_000_000_000_000_000.0);
        if (bytes >= 1_000_000_000_000L) return String.format("%.1fT", bytes / 1_000_000_000_000.0);
        if (bytes >= 1_000_000_000) return String.format("%.1fG", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000) return String.format("%.1fM", bytes / 1_000_000.0);
        if (bytes >= 1_000) return (bytes / 1_000) + "K";
        return bytes + "B";
    }


}
