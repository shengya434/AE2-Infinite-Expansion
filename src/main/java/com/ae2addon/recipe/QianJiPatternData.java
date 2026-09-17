package com.ae2addon.recipe;

import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 千机**自有样板**的数据模型（2026-09-15 sensei 定稿：独立样板体系，不强兼原版 AE2 样板）。
 * <p>
 * 用 AE2 的 {@link GenericStack} 作为内容单位 —— 它**物品与流体通吃**
 * （2026-09-15 补：原先只存 Item，把 GT 矿石清洗机这类"要耗水/产流体"的配方整条漏掉了），
 * 且自带 NBT 读写（{@code writeTag/readTag}），正好当样板数据的序列化载体。
 * <p>
 * 数据存在样板物品的 NBT 上（key = {@link #TAG_KEY}）：
 * <pre>
 * machine : 来源机器/配方类型 id（展示用）
 * recipe  : 来源配方 id（可追溯）
 * inputs  : [ { options:[GenericStack…] } ]         输入槽（options = 可接受集合，含物品与流体）
 * primary : [ GenericStack … ]                      主产物（必出）
 * chanced : [ { stack:GenericStack, chance:0.15 } ] 概率产出
 * </pre>
 */
public final class QianJiPatternData {

    /** 样板物品上的 NBT 键 */
    public static final String TAG_KEY = "***";

    /**
     * 一个输入槽。
     *
     * @param options 可接受的输入（物品/流体/化学物，各自带数量；标签类原料会有多个）
     * @param catalyst {@code true} = **非消耗输入**（GT 的 notConsumable / 模具 / 只损耐久的工具）：
     *                 千机不向 AE2 索取、不消耗它（否则会把模具当耗材吞掉）
     */
    public record Slot(List<GenericStack> options, boolean catalyst) {
        /** 普通消耗型输入槽 */
        public Slot(List<GenericStack> options) {
            this(options, false);
        }
    }

    /** 确定产出 */
    public record Out(GenericStack stack) {}

    /** 概率产出（chance: 0..1；<0 = 未声明 → 按必出处理） */
    public record Chanced(GenericStack stack, float chance) {}

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

    /**
     * 「基础配方类型」= 原版合成 / 熔炼类 / 锻造台（2026-09-17 sensei 定：未接入集成型CPU 时只允许插这类样板）。
     * <p>
     * 放在数据类里而不是方块类里，是为了让「编码匹配」与「插入门禁」用**同一份白名单**
     * （见 {@code QianJiRecipeModel.match} 的基础类型优先）。
     */
    public static boolean isBasicType(@org.jetbrains.annotations.Nullable String machine) {
        if (machine == null || machine.isEmpty()) return false;
        // ⚠ 2026-09-17 sensei 实测「本样板类型：crafting」——原版配方类型的 toString() **不带命名空间**：
        //   RecipeType.register("crafting") → new ResourceLocation("crafting") + new RecipeType$1("crafting")
        //   → toString() == "crafting"（mod 自己 create(ns, path) 的才带命名空间，如 "botania:orechid"）
        // 所以两种形态都要认，先剥掉可能存在的 minecraft: 前缀再比短名。
        String m = machine.startsWith("minecraft:") ? machine.substring("minecraft:".length()) : machine;
        if (m.startsWith("crafting")) return true;   // crafting / crafting_shaped / crafting_special_*
        return switch (m) {
            case "smelting", "blasting", "smoking", "campfire_cooking",
                 "smithing", "smithing_transform", "smithing_trim" -> true;
            default -> false;
        };
    }

    // ── NBT ──

    public CompoundTag toTag() {
        var tag = new CompoundTag();
        tag.putString("machine", machine);
        tag.putString("recipe", recipeId);

        var inputsTag = new ListTag();
        for (var slot : inputs) {
            var slotTag = new CompoundTag();
            var options = new ListTag();
            for (var option : slot.options()) {
                options.add(GenericStack.writeTag(option));
            }
            slotTag.put("options", options);
            if (slot.catalyst()) slotTag.putBoolean("catalyst", true);
            inputsTag.add(slotTag);
        }
        tag.put("inputs", inputsTag);

        var primaryTag = new ListTag();
        for (var out : primary) {
            primaryTag.add(GenericStack.writeTag(out.stack()));
        }
        tag.put("primary", primaryTag);

        var chancedTag = new ListTag();
        for (var c : chanced) {
            var cTag = new CompoundTag();
            cTag.put("stack", GenericStack.writeTag(c.stack()));
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

        var inputsTag = tag.getList("inputs", Tag.TAG_COMPOUND);
        for (int i = 0; i < inputsTag.size(); i++) {
            var optionsTag = inputsTag.getCompound(i).getList("options", Tag.TAG_COMPOUND);
            var options = new ArrayList<GenericStack>();
            for (int j = 0; j < optionsTag.size(); j++) {
                GenericStack stack = GenericStack.readTag(optionsTag.getCompound(j));
                if (stack != null && stack.amount() > 0) options.add(stack);
            }
            if (!options.isEmpty()) {
                var slotTag = inputsTag.getCompound(i);
                inputs.add(new Slot(List.copyOf(options),
                        slotTag.contains("catalyst") && slotTag.getBoolean("catalyst")));
            }
        }

        var primaryTag = tag.getList("primary", Tag.TAG_COMPOUND);
        for (int i = 0; i < primaryTag.size(); i++) {
            GenericStack stack = GenericStack.readTag(primaryTag.getCompound(i));
            if (stack != null && stack.amount() > 0) primary.add(new Out(stack));
        }

        var chancedTag = tag.getList("chanced", Tag.TAG_COMPOUND);
        for (int i = 0; i < chancedTag.size(); i++) {
            var cTag = chancedTag.getCompound(i);
            GenericStack stack = GenericStack.readTag(cTag.getCompound("stack"));
            if (stack != null && stack.amount() > 0) {
                chanced.add(new Chanced(stack, cTag.contains("chance") ? cTag.getFloat("chance") : -1f));
            }
        }

        return new QianJiPatternData(tag.getString("machine"), tag.getString("recipe"),
                inputs, primary, chanced);
    }

    /** 从物品上读（没有 = null） */
    @Nullable
    public static QianJiPatternData of(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return null;
        var tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_KEY)) return null;
        return fromTag(tag.getCompound(TAG_KEY));
    }

    /** 写到物品上 */
    public void writeTo(net.minecraft.world.item.ItemStack stack) {
        stack.getOrCreateTag().put(TAG_KEY, toTag());
    }

    // ── 展示 ──

    /** 人类可读的摘要（tooltip / 聊天栏） */
    public List<String> describe() {
        var lines = new ArrayList<String>();

        var in = new StringBuilder();
        for (var slot : inputs) {
            if (slot.options().isEmpty()) continue;
            if (!in.isEmpty()) in.append(" §7+ ");
            in.append(label(slot.options().get(0)));
            if (slot.options().size() > 1) in.append("§8(任一)");
            if (slot.catalyst()) in.append("§e(不消耗)");
        }
        lines.add("§7输入: §f" + (in.isEmpty() ? "—" : in));

        var out = new StringBuilder();
        for (var p : primary) {
            if (!out.isEmpty()) out.append("§7、");
            out.append("§a").append(label(p.stack()));
        }
        lines.add("§7主产物: " + (out.isEmpty() ? "§c—" : out));

        for (var c : chanced) {
            String pct = c.chance() > 0f ? Math.round(c.chance() * 100) + "%" : "几率未知";
            lines.add("§7概率产出: §d" + label(c.stack()) + " §8(" + pct + ")");
        }
        if (!machine.isEmpty()) lines.add("§8来源: " + machine);
        return lines;
    }

    private static String label(GenericStack stack) {
        String name = stack.what().getDisplayName().getString();
        return stack.amount() > 1 ? name + " ×" + stack.amount() : name;
    }
}
