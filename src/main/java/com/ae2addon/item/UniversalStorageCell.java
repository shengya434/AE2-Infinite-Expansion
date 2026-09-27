package com.ae2addon.item;

import appeng.api.config.FuzzyMode;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.util.ConfigInventory;
import com.ae2addon.gui.ModeSelectMenu;
import com.ae2addon.gui.InfiniteDriveMenu;
import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.util.SizeFormat;
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

import java.math.BigInteger;
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

    /**
     * Mode 2 配置菜单的 MenuProvider（静态内部类，避免匿名类 $N 加载问题）。
     * 注意：openScreen 的 buf 写入也必须用静态 Consumer 类（不能 lambda）。
     */
    private static class Mode2MenuProvider implements MenuProvider {
        private final ItemStack stack;
        private final InfiniteDriveBE drive;
        private final int driveSlot;

        Mode2MenuProvider(ItemStack stack, InfiniteDriveBE drive, int driveSlot) {
            this.stack = stack;
            this.drive = drive;
            this.driveSlot = driveSlot;
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.ae2addon.mode2_config");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new com.ae2addon.gui.Mode2ConfigMenu(id, inv, stack, drive, driveSlot);
        }
    }

    /** Mode 1/3 模式选择菜单的 MenuProvider（静态内部类） */
    private static class ModeSelectMenuProvider implements MenuProvider {
        private final ItemStack stack;
        private final InfiniteDriveBE drive;
        private final int driveSlot;

        ModeSelectMenuProvider(ItemStack stack, InfiniteDriveBE drive, int driveSlot) {
            this.stack = stack;
            this.drive = drive;
            this.driveSlot = driveSlot;
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.ae2addon.mode_select");
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new ModeSelectMenu(id, inv, stack, drive, driveSlot);
        }
    }

    /** openScreen 的 buf 写入器（静态内部类，避免 lambda 合成类问题） */
    private static class LightStackWriter implements java.util.function.Consumer<net.minecraft.network.FriendlyByteBuf> {
        private final ItemStack stack;
        private final boolean dropModeData; // true=模式1/3（额外移除 a）

        LightStackWriter(ItemStack stack, boolean dropModeData) {
            this.stack = stack;
            this.dropModeData = dropModeData;
        }

        @Override
        public void accept(net.minecraft.network.FriendlyByteBuf buf) {
            // 只传阈值，裁掉 s2/ul/wl 等重 NBT 数据以防止打开菜单时就炸包
            ItemStack copy = stack.copy();
            CompoundTag tag = copy.getOrCreateTag();
            tag.remove("s1");
            tag.remove("s2");
            tag.remove("sa");
            tag.remove("u");
            tag.remove("w");
            if (dropModeData) {
                tag.remove("a");
            }
            buf.writeItem(copy);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        if (player instanceof ServerPlayer serverPlayer) {
            openConfigFor(serverPlayer, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    /** 打开指定元件的配置菜单；手持与驱动器内元件共用这一处模式分流。 */
    public static void openConfigFor(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof UniversalStorageCell)) return;

        InfiniteDriveBE drive = null;
        int driveSlot = -1;
        if (player.containerMenu instanceof InfiniteDriveMenu menu && menu.isEditorMode()) {
            driveSlot = menu.findCellSlot(stack);
            if (driveSlot < 0) return;
            drive = menu.getDrive();
        }

        int mode = stack.getOrCreateTag().getInt("umode");
        if (mode < 1 || mode > 3) mode = 1;
        if (mode == MODE_CUSTOM) {
            NetworkHooks.openScreen(player,
                    new Mode2MenuProvider(stack, drive, driveSlot),
                    new LightStackWriter(stack, false));
        } else {
            // Mode 1 / Mode 3：只传光副本，裁掉存储NBT防炸包
            NetworkHooks.openScreen(player,
                    new ModeSelectMenuProvider(stack, drive, driveSlot),
                    new LightStackWriter(stack, true));
        }
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
        String[] n = {"", "gui.ae2addon.mode.unlimited", "gui.ae2addon.mode.custom", "gui.ae2addon.mode.all"};
        if (m < 1 || m > 3) m = 1;
        return Component.translatable("gui.ae2addon.cell.name", Component.translatable(n[m]));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getOrCreateTag();
        int m = tag.getInt("umode");
        if (m < 1 || m > 3) m = 1;

        String[][] info = {
                {},
                {"gui.ae2addon.cell.mode1.name", "gui.ae2addon.cell.mode1.desc"},
                {"gui.ae2addon.cell.mode2.name", "gui.ae2addon.cell.mode2.desc"},
                {"gui.ae2addon.cell.mode3.name", "gui.ae2addon.cell.mode3.desc"}
        };
        tooltip.add(Component.translatable("gui.ae2addon.cell.mode", Component.translatable(info[m][0])));
        tooltip.add(Component.translatable(info[m][1]));

        // Mode 3 直接显示 ∞
        if (m == 3) {
            tooltip.add(Component.translatable("gui.ae2addon.cell.bytes", "∞"));
            tooltip.add(Component.translatable("gui.ae2addon.cell.types", "∞"));
            tooltip.add(Component.translatable("gui.ae2addon.cell.limit"));
            tooltip.add(Component.translatable("gui.ae2addon.cell.switch_hint"));
            return;
        }

        // Mode 1 / 2：从统计摘要标签读（轻量，不遍历几千条 NBT）
        BigInteger totalBytes;
        if (tag.contains("_b2", 7)) {
            totalBytes = new BigInteger(tag.getByteArray("_b2"));
        } else {
            totalBytes = BigInteger.valueOf(tag.getLong("_b"));
        }
        int typeCount = tag.getInt("_t");

        tooltip.add(Component.translatable("gui.ae2addon.cell.bytes", SizeFormat.bytes(totalBytes)));
        tooltip.add(Component.translatable("gui.ae2addon.cell.types", typeCount));
        tooltip.add(Component.translatable("gui.ae2addon.cell.switch_hint"));
    }

}
