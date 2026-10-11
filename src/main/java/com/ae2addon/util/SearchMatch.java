package com.ae2addon.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.oredict.OreDictionary;

/**
 * The config panel's one and only fuzzy-search predicate (2026-10-11, sensei: "模糊搜索的范围好像小了点，
 * 最好能搜矿词或 modid").
 *
 * <p><b>Why a shared class instead of a second copy of the rules.</b> The search runs in two places:
 * the server ({@code ChannelCellCore.searchRegistry}, which walks the whole registry for the batch-infinite tab)
 * and the client ({@code Mode2ConfigScreen.applyFilters}, which filters the rows the panel already has).
 * If those two ever drifted apart, the panel would show a row that the batch action then refuses to touch
 * (or the reverse) -- so the matching rules live here once and both call sites use them.
 *
 * <p><b>What a query is matched against</b>, in cost order (cheap checks first, so the expensive ones only run
 * for rows that would otherwise be rejected):
 * <ol>
 *   <li>the registry id (plain substring) -- so {@code minecraft:stone} and a bare modid like {@code thermal}
 *       both hit;</li>
 *   <li>the display name, plus pinyin initials and full pinyin for ASCII queries (see {@link PinyinIndex});</li>
 *   <li><b>ore dictionary names</b> (items only, and only when the stack is known) -- typing {@code ingot}
 *       finds every ingot, {@code dustRedstone} finds redstone dust. The {@code ore:} prefix is accepted and
 *       stripped, so {@code ore:ingot} works too;</li>
 *   <li><b>the mod id and the mod's display name</b> -- {@code mod:thermal} is the explicit form; a bare query
 *       also matches the mod name, which is how you find things when the id and the name differ
 *       ("immersive engineering" has no space in {@code immersiveengineering:...});</li>
 *   <li>as a last resort for <b>non-item rows</b> (fluids, gases, essentia, mana, flux): any {@code modid:path}
 *       shaped string inside the row's key NBT. The client cannot ask an arbitrary channel for its registry id
 *       (that API is server side), and for those rows the item representation is a bucket, so this is what keeps
 *       "search a fluid by its mod" working on the client.</li>
 * </ol>
 *
 * <p>Nothing here mutates or caches per-key state: it is a pure predicate, safe to call for thousands of keys.
 */
public final class SearchMatch {

    /** Lazily built {@code modid(lowercase) -> mod display name}; {@code Loader} is available on both sides. */
    private static Map<String, String> modDisplayNames;

    private SearchMatch() {
    }

    /**
     * @param query       the raw query text (null/blank = match everything)
     * @param displayName the row's display name (may be null)
     * @param registryId  the key's registry id when the caller has one (may be null)
     * @param stack       supplies the item stack form of the key <b>lazily</b>: building it is only needed once
     *                    the name/id checks failed, and on the server that is every row that does not match
     * @param keyNbt      the row's own key NBT, used for the non-item fallback (may be null)
     */
    public static boolean matches(String query, @Nullable String displayName, @Nullable String registryId,
                                  @Nullable Supplier<ItemStack> stack, @Nullable NBTTagCompound keyNbt) {
        return matches(query, displayName, registryId, stack, keyNbt, null);
    }

    /**
     * Client-side convenience: the row already carries a concrete stack, and there is no need to know the
     * key NBT when the caller has the channel's registry id.
     */
    public static boolean matches(String query, @Nullable String displayName, @Nullable String registryId,
                                  @Nullable ItemStack stack, @Nullable NBTTagCompound keyNbt) {
        return matches(query, displayName, registryId, stack == null ? null : () -> stack, keyNbt, stack);
    }

    private static boolean matches(String query, @Nullable String displayName, @Nullable String registryId,
                                   @Nullable Supplier<ItemStack> stackSupplier, @Nullable NBTTagCompound keyNbt,
                                   @Nullable ItemStack knownStack) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return true;
        }
        // ①/② 注册 id、显示名、拼音（老口径，保持不变）
        if (PinyinIndex.matches(q, displayName, registryId)) {
            return true;
        }
        String lower = q.toLowerCase(Locale.ROOT);

        // 下面几条才需要"实物"，所以放在便宜检查之后
        ItemStack stack = knownStack;
        if (stack == null && stackSupplier != null) {
            try {
                stack = stackSupplier.get();
            } catch (Throwable ignored) {
                // 某个通道的表示栈构造失败不该让整次搜索炸掉
            }
        }
        if (matchesOre(lower, stack)) {
            return true;
        }
        if (matchesMod(lower, stack, registryId)) {
            return true;
        }
        return matchesKeyNbt(lower, keyNbt);
    }

    // ── 矿词 ───────────────────────────────────────────────────────────────

    /** Items only: any of the stack's ore dictionary names contains the query (the {@code ore:} prefix is stripped). */
    private static boolean matchesOre(String lower, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String needle = stripPrefix(lower, "ore:");
        if (needle.isEmpty()) {
            return false;
        }
        int[] ids = OreDictionary.getOreIDs(stack);
        for (int id : ids) {
            String name = OreDictionary.getOreName(id);
            if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ── modid / mod 名 ─────────────────────────────────────────────────────

    /** The mod id (namespace) or the mod's display name contains the query ({@code mod:} prefix stripped). */
    private static boolean matchesMod(String lower, @Nullable ItemStack stack, @Nullable String registryId) {
        String needle = stripPrefix(lower, "mod:");
        if (needle.isEmpty()) {
            return false;
        }
        String namespace = namespaceOf(stack, registryId);
        if (namespace == null) {
            return false;
        }
        if (namespace.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String name = modDisplayName(namespace);
        return name != null && name.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Nullable
    private static String namespaceOf(@Nullable ItemStack stack, @Nullable String registryId) {
        if (stack != null && !stack.isEmpty() && stack.getItem().getRegistryName() != null) {
            return stack.getItem().getRegistryName().getNamespace();
        }
        if (registryId != null) {
            int colon = registryId.indexOf(':');
            if (colon > 0) {
                return registryId.substring(0, colon);
            }
        }
        return null;
    }

    @Nullable
    private static String modDisplayName(String modId) {
        Map<String, String> names = modDisplayNames;
        if (names == null) {
            names = new HashMap<>();
            try {
                for (ModContainer container : Loader.instance().getIndexedModList().values()) {
                    if (container.getModId() != null && container.getName() != null) {
                        names.put(container.getModId().toLowerCase(Locale.ROOT), container.getName());
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到 mod 名只影响"按名字搜"，不该影响搜索本身
            }
            modDisplayNames = names;
        }
        return names.get(modId.toLowerCase(Locale.ROOT));
    }

    // ── 非物品键兜底 ───────────────────────────────────────────────────────

    /**
     * Non-item rows: any {@code modid:path} shaped string inside the key NBT. Deliberately narrow -- strings with
     * no colon or with whitespace are ignored, so a fluid's display name or a free-form note cannot produce a hit.
     */
    private static boolean matchesKeyNbt(String lower, @Nullable NBTTagCompound tag) {
        // ⚠ 1.12.2 的 NBTTagCompound **没有 hasNoTags()**（那是更早版本的 API，2026-10-11 编译报"找不到符号"），
        //    这里用 getKeySet().isEmpty() 等价判断。
        if (tag == null || tag.getKeySet().isEmpty()) {
            return false;
        }
        String needle = stripPrefix(lower, "mod:");
        if (needle.isEmpty()) {
            return false;
        }
        List<String> strings = new ArrayList<>(8);
        collectStrings(tag, strings);
        for (String value : strings) {
            int colon = value.indexOf(':');
            if (colon <= 0 || value.indexOf(' ') >= 0 || colon == value.length() - 1) {
                continue;
            }
            if (value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStrings(NBTBase tag, List<String> out) {
        if (tag instanceof NBTTagString) {
            out.add(((NBTTagString) tag).getString());
        } else if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (String key : compound.getKeySet()) {
                collectStrings(compound.getTag(key), out);
            }
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            for (int i = 0; i < list.tagCount(); i++) {
                collectStrings(list.get(i), out);
            }
        }
    }

    private static String stripPrefix(String lower, String prefix) {
        return lower.startsWith(prefix) ? lower.substring(prefix.length()).trim() : lower;
    }
}
