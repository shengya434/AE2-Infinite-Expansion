package com.ae2addon.recipe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 千机**自有样板**的数据模型（2026-09-15 sensei 定稿：独立样板体系，不强兼原版 AE2 样板）。
 * <p>
 * 为什么自建：原版处理样板只能表达「输入 → 输出（数量）」，**没有概率的概念** ——
 * 副产只能写成"必出"，或者靠运行时反推各 mod 配方去猜几率（GT 一个产物多条配方时就会猜错）。
 * 我们自己的样板把「概率产出」显式写进数据里，千机读到就是**精确执行**，不猜。
 * <p>
 * 数据存在样板物品的 NBT 上（key = {@link #TAG_KEY}）：
 * <pre>
 * machine : 来源机器/配方类型 id（展示用）
 * recipe  : 来源配方 id（可追溯）
 * inputs  : [ { options:[物品id…], count:N } ]     输入槽（options = 标签展开后的可接受集合）
 * primary : [ { item:id, count:N } ]               主产物（必出）
 * chanced : [ { item:id, count:N, chance:0.15 } ]  概率产出
 * </pre>
 */
public final class QianJiPatternData {

    /** 样板物品上的 NBT 键 */
    public static final String TAG_KEY = "ae2addon:qianji_pattern";

    /** 确定产出 */
    public record Out(Item item, int count) {}

    /** 概率产出（chance: 0..1；<0 = 未声明 → 按必出处理） */
    public record Chanced(Item item, int count, float chance) {}

    /** 一个输入槽：options = 可接受的物品集合（标签类原料展开后的代表集），count = 数量 */
    public record Slot(List<Item> options, int count) {}

    private final String machine;
    private final String recipeId;
    private final List<Slot> inputs;
    private final List<Out> primary;
    private final List<Chanced> chanced;

    public QianJiPatternData(String machine, String recipeId,
                             List<Slot> inputs, List<Out> primary, List<Chanced> chanced) {
        this.machine = machine == null ? "" : machine;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.inputs = List.copyOf(inputs);
        this.primary = List.copyOf(primary);
        this.chanced = List.copyOf(chanced);
    }

    public String machine() { return machine; }
    public String recipeId() { return recipeId; }
    public List<Slot> inputs() { return inputs; }
    public List<Out> primary() { return primary; }
    public List<Chanced> chanced() { return chanced; }

    public boolean isEmpty() { return inputs.isEmpty() && primary.isEmpty() && chanced.isEmpty(); }

    // ── NBT ──

    public CompoundTag toTag() {
        var tag = new CompoundTag();
        tag.putString("machine", machine);
        tag.putString("recipe", recipeId);

        var inputsTag = new ListTag();
        for (var slot : inputs) {
            var slotTag = new CompoundTag();
            var options = new ListTag();
            for (var item : slot.options()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) options.add(StringTag.valueOf(id.toString()));
            }
            slotTag.put("options", options);
            slotTag.putInt("count", slot.count());
            inputsTag.add(slotTag);
        }
        tag.put("inputs", inputsTag);

        var primaryTag = new ListTag();
        for (var out : primary) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(out.item());
            if (id == null) continue;
            var outTag = new CompoundTag();
            outTag.putString("item", id.toString());
            outTag.putInt("count", out.count());
            primaryTag.add(outTag);
        }
        tag.put("primary", primaryTag);

        var chancedTag = new ListTag();
        for (var c : chanced) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(c.item());
            if (id == null) continue;
            var cTag = new CompoundTag();
            cTag.putString("item", id.toString());
            cTag.putInt("count", c.count());
            cTag.putFloat("chance", c.chance());
            chancedTag.add(cTag);
        }
        tag.put("chanced", chancedTag);
        return tag;
    }

    public static QianJiPatternData fromTag(CompoundTag tag) {
        var inputs = new ArrayList<Slot>();
        var primary = new ArrayList<Out>();
        var chanced = new ArrayList<Chanced>();

        var inputsTag = tag.getList("inputs", 10);
        for (int i = 0; i < inputsTag.size(); i++) {
            var slotTag = inputsTag.getCompound(i);
            var options = new ArrayList<Item>();
            var optionsTag = slotTag.getList("options", 8);
            for (int j = 0; j < optionsTag.size(); j++) {
                Item item = itemOf(optionsTag.getString(j));
                if (item != null) options.add(item);
            }
            if (!options.isEmpty()) inputs.add(new Slot(options, Math.max(1, slotTag.getInt("count"))));
        }

        var primaryTag = tag.getList("primary", 10);
        for (int i = 0; i < primaryTag.size(); i++) {
            var outTag = primaryTag.getCompound(i);
            Item item = itemOf(outTag.getString("item"));
            if (item != null) primary.add(new Out(item, Math.max(1, outTag.getInt("count"))));
        }

        var chancedTag = tag.getList("chanced", 10);
        for (int i = 0; i < chancedTag.size(); i++) {
            var cTag = chancedTag.getCompound(i);
            Item item = itemOf(cTag.getString("item"));
            if (item != null) {
                chanced.add(new Chanced(item, Math.max(1, cTag.getInt("count")),
                        cTag.contains("chance") ? cTag.getFloat("chance") : -1f));
            }
        }

        return new QianJiPatternData(tag.getString("machine"), tag.getString("recipe"),
                inputs, primary, chanced);
    }

    /** 从物品上读（没有 = null） */
    @Nullable
    public static QianJiPatternData of(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return null;
        var tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_KEY)) return null;
        return fromTag(tag.getCompound(TAG_KEY));
    }

    /** 写到物品上 */
    public void writeTo(ItemStack stack) {
        stack.getOrCreateTag().put(TAG_KEY, toTag());
    }

    @Nullable
    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    // ── 展示 ──

    /** 人类可读的摘要（tooltip / 聊天栏） */
    public List<String> describe() {
        var lines = new ArrayList<String>();
        var in = new StringBuilder();
        for (var slot : inputs) {
            if (!in.isEmpty()) in.append(" + ");
            in.append(displayName(slot.options().get(0)));
            if (slot.count() > 1) in.append(" ×").append(slot.count());
            if (slot.options().size() > 1) in.append("(任一)");
        }
        lines.add("§7输入: §f" + (in.isEmpty() ? "—" : in));
        var out = new StringBuilder();
        for (var p : primary) {
            if (!out.isEmpty()) out.append("、");
            out.append(displayName(p.item()));
            if (p.count() > 1) out.append(" ×").append(p.count());
        }
        lines.add("§7主产物: §a" + (out.isEmpty() ? "—" : out));
        for (var c : chanced) {
            String pct = c.chance() > 0f ? Math.round(c.chance() * 100) + "%" : "几率未知";
            lines.add("§7概率产出: §d" + displayName(c.item())
                    + (c.count() > 1 ? " ×" + c.count() : "") + " §8(" + pct + ")");
        }
        if (!machine.isEmpty()) lines.add("§8来源: " + machine);
        return lines;
    }

    private static String displayName(Item item) {
        return item.getDescription().getString();
    }
}
