package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ae2addon.AE2Addon;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The item channel: everything item-specific that the judgement layer must not know about.
 *
 * <p>Moved out of {@code UnlimitedCellInventory} on 2026-10-03 (P4 step 1) so that adding fluid/gas/essentia
 * means writing another adapter instead of touching the modes, thresholds, rules and listing logic.
 *
 * <p>Ore names live here on purpose: 1.12.2 has no item tags (those arrive in 1.13), so "batch rules by
 * material" is OreDictionary -- and OreDictionary only exists for items. {@link #matchesOre} is therefore
 * false for every other channel, which is exactly the semantics sensei settled on (ore rules are item-only;
 * every channel gets mod rules, whitelist, blacklist, thresholds and quantity locks).
 */
public final class ItemCellChannel implements CellChannel<IAEItemStack> {

    public static final ItemCellChannel INSTANCE = new ItemCellChannel();

    private ItemCellChannel() {
    }

    @Override
    public String id() {
        return "item";
    }

    @Override
    public IStorageChannel<IAEItemStack> storage() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    @Override
    public IAEItemStack readNbt(NBTTagCompound tag) {
        return AEItemStack.fromNBT(tag);
    }

    @Override
    public IAEItemStack normalize(IAEItemStack key) {
        IAEItemStack copy = key.copy();
        copy.setStackSize(1);
        return copy;
    }

    @Override
    public String namespace(IAEItemStack key) {
        ItemStack def = key.getDefinition();
        if (def == null || def.isEmpty()) {
            return null;
        }
        net.minecraft.util.ResourceLocation id = def.getItem().getRegistryName();
        return id == null ? null : id.getNamespace();
    }

    @Override
    public boolean hasExtraData(IAEItemStack key) {
        ItemStack def = key.getDefinition();
        return def != null && !def.isEmpty() && def.hasTagCompound();
    }

    @Override
    public Set<String> matchedOreRules(IAEItemStack key, Set<String> oreRules) {
        if (oreRules == null || oreRules.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        ItemStack def = key.getDefinition();
        if (def == null || def.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(def);
        if (ids == null || ids.length == 0) {
            return java.util.Collections.emptySet();
        }
        Set<String> hit = null;
        for (int id : ids) {
            String ore = net.minecraftforge.oredict.OreDictionary.getOreName(id);
            if (ore != null && oreRules.contains(ore)) {
                if (hit == null) {
                    hit = new LinkedHashSet<>();
                }
                hit.add(ore);
            }
        }
        return hit == null ? java.util.Collections.emptySet() : hit;
    }

    @Override
    public String displayName(IAEItemStack key) {
        ItemStack def = key.getDefinition();
        return def == null || def.isEmpty() ? String.valueOf(key) : def.getDisplayName();
    }

    @Override
    public long defaultThreshold() {
        return UnlimitedCellInventory.DEFAULT_THRESHOLD;
    }

    // ------------------------------------------------------------------
    // full enumeration (mode 3 "everything is infinite" + the Instant-rule scan)
    // ------------------------------------------------------------------

    /** Cached forever: the item registry is frozen once the game has loaded. Read-only for callers. */
    private transient List<IAEItemStack> allKeyCache = null;

    /**
     * Canonical key for every registered item, <b>metadata variants included</b>, no NBT.
     *
     * <p>Meta 0 alone is not enough: a pack registers one Item with many metas and nothing else distinguishes
     * them. Mekanism CE's {@code ItemIngot} implements {@code IMetaItem}, so refined obsidian and osmium share
     * one item id and differ only by meta -- scanning just {@code new ItemStack(item)} made an Instant rule
     * infinite for the first meta of such a sequence and left every other meta Touch-only. That is
     * 2026-10-03 bug 3 (sensei: "立即无限只会无限某一序列号的第一位物品").
     *
     * <p>So every item is asked for its sub-items the way the creative inventory does it
     * ({@code Item#getSubItems}). {@code CreativeTabs.displayAllRelevantItems} is deliberately not used: it
     * is client-only, and this scan also runs on the server. Items that refuse to answer fall back to
     * meta 0, and a mod whose getSubItems throws cannot kill the scan.
     *
     * <p>Tagged stacks are skipped on purpose: the canonical key is (item, meta, no NBT) -- the rule
     * predicate cannot speak about NBT variants, and mode 3 reports the ones we have actually seen separately.
     */
    @Override
    public List<IAEItemStack> allKeys() {
        if (allKeyCache != null) {
            return allKeyCache;
        }
        List<IAEItemStack> found = new ArrayList<>();
        Set<IAEItemStack> seen = new LinkedHashSet<>();
        for (net.minecraft.util.ResourceLocation id : Item.REGISTRY.getKeys()) {
            Item item = Item.REGISTRY.getObject(id);
            // minecraft:air is IN the item registry, and new ItemStack(air) is an EMPTY stack, for which
            // AEItemStack.fromItemStack returns null -- that NPE'd the whole server tick loop on 2026-10-02
            // (thrown from the listing path, via getAvailableItems <- TileDrive.recalculateDisplay). Skip it,
            // and skip anything else that cannot produce a key, instead of trusting every registry entry to
            // be convertible.
            if (item == null || item == net.minecraft.init.Items.AIR) {
                continue;
            }
            for (ItemStack def : subItemStacks(item)) {
                IAEItemStack key = AEItemStack.fromItemStack(def);
                if (key == null) {
                    continue;
                }
                if (seen.add(normalize(key))) {
                    found.add(key);
                }
            }
        }
        allKeyCache = found;
        return found;
    }

    /** Every stack this item offers to the creative inventory, meta 0 when it will not answer. */
    private static List<ItemStack> subItemStacks(Item item) {
        List<ItemStack> out = new ArrayList<>();
        collectSubItems(item, net.minecraft.creativetab.CreativeTabs.SEARCH, out);
        // Mods that gate their variants on their own tab rather than going through isInCreativeTab only
        // answer for that tab, so ask those too.
        net.minecraft.creativetab.CreativeTabs[] tabs = item.getCreativeTabs();
        if (tabs != null) {
            for (net.minecraft.creativetab.CreativeTabs tab : tabs) {
                collectSubItems(item, tab, out);
            }
        }
        if (out.isEmpty()) {
            out.add(new ItemStack(item));
        }
        return out;
    }

    private static void collectSubItems(Item item, net.minecraft.creativetab.CreativeTabs tab,
            List<ItemStack> out) {
        if (tab == null) {
            return;
        }
        try {
            net.minecraft.util.NonNullList<ItemStack> sub = net.minecraft.util.NonNullList.create();
            item.getSubItems(tab, sub);
            for (ItemStack stack : sub) {
                if (stack == null || stack.isEmpty() || stack.getItem() == net.minecraft.init.Items.AIR
                        || stack.hasTagCompound()) {
                    continue;
                }
                out.add(stack);
            }
        } catch (Throwable t) {
            // One bad mod must not cost us the whole scan: fall back to whatever else answered (meta 0 at
            // the very least).
            AE2Addon.LOG.warn("[ae2addon] {} 的 getSubItems 失败，跳过该来源：{}", item.getRegistryName(), t);
        }
    }
}
