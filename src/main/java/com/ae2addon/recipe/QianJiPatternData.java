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
        // 2026-09-19：**千机自用配方**（ae2addon:qianji_recipe）是千机自己执行的配方，
        // 不依赖网络里存在某台真实机器 → 没接集成型CPU 时也必须能插进千机样板槽（sensei 的这批配方要用它）
        if (m.equals(QianJiRecipe.MACHINE_ID)) return true;
        if (m.startsWith("crafting")) return true;   // crafting / crafting_shaped / crafting_special_*
        return switch (m) {
            case "smelting", "blasting", "smoking", "campfire_cooking",
                 "stonecutting",   // 2026-09-19 补：切石机也是原版基础配方（此前漏了，见 design §11）
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
        writeAeSearchOut(stack);
    }

    /**
     * 2026-09-20 sensei：**样板管理终端里按产物搜不到千机样板**。
     * <p>
     * 反汇编 AE2 的 {@code PatternAccessTermScreen#itemStackMatchesSearchTerm} 得到真因：
     * 它只读物品 NBT 里的 **{@code out}** 列表（AE2 自己样板的产物格式）——
     * {@code stack.getTag().getList("out", COMPOUND)} → 逐个 {@code ItemStack.of(compound)} →
     * {@code AEItemKey.getDisplayName()} → 含搜索词即命中。
     * 我们的数据写在 {@link #TAG_KEY}（{@code ***}）下，**没有 {@code out}** → 永远搜不到。
     * <p>
     * 处置：额外同步写一份 {@code out}（**只写物品形态**的产物，格式与 AE2 一致：每个元素是一个 ItemStack 的 NBT）。
     * 主数据一个字节都不动；AE2 的其它读取路径都是"按物品类型分派到各解码器"，
     * 本来就不认 {@code ae2addon:qianji_pattern}，所以多这个键不会让它们误认。
     */
    private void writeAeSearchOut(net.minecraft.world.item.ItemStack stack) {
        var outList = new ListTag();
        for (var out : primary) {
            addSearchOut(outList, out.stack());
        }
        for (var chanced : chanced) {
            addSearchOut(outList, chanced.stack());
        }
        stack.getOrCreateTag().put("out", outList);
    }

    private static void addSearchOut(ListTag list, GenericStack gs) {
        if (gs == null || !(gs.what() instanceof appeng.api.stacks.AEItemKey itemKey)) {
            return;   // 流体/化学品没有物品形态 → 不进 AE2 的搜索列表（它的搜索只认 ItemStack）
        }
        var tag = new CompoundTag();
        itemKey.toStack(1).save(tag);
        list.add(tag);
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

    // ── 搜索文本（2026-09-20 sensei：千机 GUI 内的样板搜索） ──

    /**
     * 检索文本：把一张样板里"人会想起来的字段"全拼成一串**小写纯文本**，供子串匹配。
     * <p>
     * 覆盖：配方类型/机器（{@link #machine}）、配方 id、**全部输入**（含备选集合）、主产物、概率产出。
     * 每个堆取 {@code getDisplayName()} 之外**还带注册名** —— 中文名不好打的时候可以直接搜
     * {@code mekanism:ingot_steel} 这种 id。
     * <p>
     * 与 {@link #describe()} 的分工：那边是给人看的（带 §颜色码、多行）；这边只求"纯文本 + contains"，
     * 所以**不能**复用 describe，否则颜色码会混进匹配串里。
     */
    public String searchText() {
        var sb = new StringBuilder(160);
        if (!machine.isEmpty()) sb.append(machine).append(' ');
        if (!recipeId.isEmpty()) sb.append(recipeId).append(' ');
        for (var slot : inputs) {
            for (var option : slot.options()) appendSearch(sb, option);
        }
        for (var out : primary) appendSearch(sb, out.stack());
        for (var chanced : chanced) appendSearch(sb, chanced.stack());
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void appendSearch(StringBuilder sb, GenericStack stack) {
        if (stack == null) return;
        sb.append(stack.what().getDisplayName().getString()).append(' ');
        String id = registryIdOf(stack.what());
        if (id != null) sb.append(id).append(' ');
    }

    /**
     * 注册名：只对**有物品/流体形态**的键有效（用 BuiltInRegistries 取，仓库里已有同样写法）。
     * 化学品之类没有对应注册表的返回 {@code null} —— 宁可不带，也不猜一个错的进去。
     */
    private static String registryIdOf(appeng.api.stacks.AEKey key) {
        if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(itemKey.getItem()).toString();
        }
        if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
            return net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()).toString();
        }
        return null;
    }
}
