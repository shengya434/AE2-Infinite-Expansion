package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 页「千机·自用配方」（2026-09-15 sensei 定稿）。
 * <p>
 * 版面（2026-09-15 二次优化）：
 * <ul>
 *   <li>**固定槽位网格**：物品输入 9 格 / 物品输出 9 格 / 流体输入 2 格 / 流体输出 2 格 —— 空着也画出来</li>
 *   <li>**不够自动扩槽**：物品每 9 格一行、流体每 2 格一行，按需加行（上限见 MAX_* 常量）</li>
 *   <li>**非物品一律进流体槽**：槽位按第一个选项的类型分类（物品 → 物品槽，其余 → 流体槽），
 *       以后接别 mod 的非物品输入（气体/化学物等）也自动落到流体槽</li>
 *   <li>右下「编码」按钮 → 生成千机配方样板（成本与 AE2 编码一致）</li>
 * </ul>
 */
public class QianJiRecipeCategory implements IRecipeCategory<QianJiRecipeCategory.Entry> {

    public static final RecipeType<Entry> TYPE =
            new RecipeType<>(new ResourceLocation(AE2Addon.MODID, "qianji_recipes"), Entry.class);

    // ── 版面常量 ──
    private static final int WIDTH = 176;
    private static final int EDGE = 6;
    private static final int STEP = 18;
    /** 物品槽每行几个 */
    private static final int ITEMS_PER_ROW = 9;
    /** 流体槽每行几个 */
    private static final int FLUIDS_PER_ROW = 2;
    /** 上限（超出只显示前 N 个） */
    private static final int MAX_ITEM_SLOTS = 18;
    private static final int MAX_FLUID_SLOTS = 4;

    private static final int Y_ITEM_IN = 22;
    private static final int Y_FLUID_IN = 62;
    private static final int Y_ITEM_OUT = 118;
    private static final int Y_FLUID_OUT = 158;
    private static final int HEIGHT = 214;

    /** 「编码」按钮区域（页面内坐标） */
    private static final int BTN_X = 146;
    private static final int BTN_Y = 190;
    private static final int BTN_W = 24;
    private static final int BTN_H = 16;

    private final IDrawable icon;
    private final IDrawable arrow;
    /** 槽位底板（空槽也要看得见 → 我们自己画） */
    private final IDrawable slotDrawable;

    /** 一条配方：来源 id + **变体序**（同一配方可能出多张：序列装配的步骤样板）+ 数据 */
    public record Entry(String recipeId, int variant, String label, QianJiPatternData data) {}

    public QianJiRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(ModItems.QIAN_JI_PATTERN.get()));
        this.arrow = guiHelper.getRecipeArrow();
        this.slotDrawable = guiHelper.getSlotDrawable();
    }

    public static Entry of(Recipe<?> recipe, QianJiPatternData data) {
        return of(recipe, 0, "", data);
    }

    public static Entry of(Recipe<?> recipe, int variant, String label, QianJiPatternData data) {
        // id 可能为空（GT 的运行时配方）→ 走稳定 id 兜底（与「编码」按钮的解析口径一致）
        return new Entry(com.ae2addon.compat.GregTechRuntimeCompat.stableId(recipe),
                variant, label == null ? "" : label, data);
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.qianji.title");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    // ── 版面计算（setRecipe 与 draw 共用，避免坐标漂移）──

    /** 槽位坐标：每行 perRow 个，步长 STEP */
    private static List<int[]> grid(int count, int perRow, int startX, int startY) {
        var points = new ArrayList<int[]>();
        for (int i = 0; i < count; i++) {
            points.add(new int[]{startX + (i % perRow) * STEP, startY + (i / perRow) * STEP});
        }
        return points;
    }

    /** 输入槽按「物品 / 非物品（流体/化学物等）」分类；非物品一律进流体槽（并带非消耗标记） */
    private static List<QianJiPatternData.Slot>[] splitInputs(QianJiPatternData data) {
        var items = new ArrayList<QianJiPatternData.Slot>();
        var fluids = new ArrayList<QianJiPatternData.Slot>();
        for (QianJiPatternData.Slot slot : data.inputs()) {
            if (slot.options().isEmpty()) continue;
            boolean isItem = slot.options().get(0).what() instanceof AEItemKey;
            (isItem ? items : fluids).add(slot);
        }
        return new List[]{items, fluids};
    }

    /** 产出按物品/非物品分类（主产物 + 概率产出同列，概率产出在 draw 里标 % ） */
    private static List<GenericStack>[] splitOutputs(QianJiPatternData data) {
        var items = new ArrayList<GenericStack>();
        var fluids = new ArrayList<GenericStack>();
        for (QianJiPatternData.Out out : data.primary()) {
            (out.stack().what() instanceof AEItemKey ? items : fluids).add(out.stack());
        }
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            (chanced.stack().what() instanceof AEItemKey ? items : fluids).add(chanced.stack());
        }
        return new List[]{items, fluids};
    }

    /** 版面尺寸：固定基线 + 按需扩槽后的格数（setRecipe 与 draw 共用） */
    private record Layout(int itemInCells, int fluidInCells, int itemOutCells, int fluidOutCells) {}

    private static Layout layoutOf(QianJiPatternData data) {
        var inSplit = splitInputs(data);
        var outSplit = splitOutputs(data);
        int itemIn = Math.max(ITEMS_PER_ROW, Math.min(inSplit[0].size(), MAX_ITEM_SLOTS));
        int fluidIn = Math.max(FLUIDS_PER_ROW, Math.min(inSplit[1].size(), MAX_FLUID_SLOTS));
        int itemOut = Math.max(ITEMS_PER_ROW, Math.min(outSplit[0].size(), MAX_ITEM_SLOTS));
        int fluidOut = Math.max(FLUIDS_PER_ROW, Math.min(outSplit[1].size(), MAX_FLUID_SLOTS));
        return new Layout(itemIn, fluidIn, itemOut, fluidOut);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Entry entry, IFocusGroup focuses) {
        QianJiPatternData data = entry.data();
        var layout = layoutOf(data);

        var inSplit = splitInputs(data);
        var itemIn = inSplit[0];
        var fluidIn = inSplit[1];

        // 物品输入（固定 9 格起，不够扩行；空槽只画底板，不声明 ingredient）
        var itemInPoints = grid(layout.itemInCells(), ITEMS_PER_ROW, EDGE, Y_ITEM_IN);
        for (int i = 0; i < Math.min(itemIn.size(), itemInPoints.size()); i++) {
            var point = itemInPoints.get(i);
            addOptions(builder.addInputSlot(point[0], point[1]), itemIn.get(i).options());
        }

        // 流体（非物品）输入
        var fluidInPoints = grid(layout.fluidInCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_IN);
        for (int i = 0; i < Math.min(fluidIn.size(), fluidInPoints.size()); i++) {
            var point = fluidInPoints.get(i);
            addOptions(builder.addInputSlot(point[0], point[1]), fluidIn.get(i).options());
        }

        var outSplit = splitOutputs(data);
        var itemOut = outSplit[0];
        var fluidOut = outSplit[1];

        var itemOutPoints = grid(layout.itemOutCells(), ITEMS_PER_ROW, EDGE, Y_ITEM_OUT);
        for (int i = 0; i < Math.min(itemOut.size(), itemOutPoints.size()); i++) {
            var point = itemOutPoints.get(i);
            addStack(builder.addOutputSlot(point[0], point[1]), itemOut.get(i));
        }

        var fluidOutPoints = grid(layout.fluidOutCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_OUT);
        for (int i = 0; i < Math.min(fluidOut.size(), fluidOutPoints.size()); i++) {
            var point = fluidOutPoints.get(i);
            addStack(builder.addOutputSlot(point[0], point[1]), fluidOut.get(i));
        }
    }

    private static void addOptions(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder,
                                   List<GenericStack> options) {
        for (GenericStack option : options) {
            addStack(slotBuilder, option);
        }
    }

    private static void addStack(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder, GenericStack stack) {
        if (stack == null || stack.what() == null) return;
        if (stack.what() instanceof AEItemKey itemKey) {
            slotBuilder.addItemStack(itemKey.toStack((int) Math.max(1, stack.amount())));
        } else if (stack.what() instanceof AEFluidKey fluidKey) {
            // 带 NBT 的流体（GT 药水：药水种类就在 NBT 里）必须把 tag 一起交给 JEI，
            // 否则页面上所有药水都长一个样（GT 注册了 PotionFluidSubtypeInterpreter 来做子类型区分）
            slotBuilder.addFluidStack(fluidKey.getFluid(), stack.amount(), fluidKey.copyTag());
        } else {
            // 非物品非流体（MEK 气体/灌注/颜料/浆液）：按既定设计进流体槽，用 MEK 的 JEI ingredient 渲染
            MekanismJeiCompat.addChemical(slotBuilder, stack.what(), stack.amount());
        }
    }

    @Override
    public void draw(Entry entry, IRecipeSlotsView slotsView, GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        QianJiPatternData data = entry.data();
        var layout = layoutOf(data);

        // ── 槽位底板：固定网格 + 扩槽，空槽也要看得见 ──
        for (var point : grid(layout.itemInCells(), ITEMS_PER_ROW, EDGE, Y_ITEM_IN)) {
            slotDrawable.draw(graphics, point[0] - 1, point[1] - 1);
        }
        for (var point : grid(layout.fluidInCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_IN)) {
            slotDrawable.draw(graphics, point[0] - 1, point[1] - 1);
        }
        for (var point : grid(layout.itemOutCells(), ITEMS_PER_ROW, EDGE, Y_ITEM_OUT)) {
            slotDrawable.draw(graphics, point[0] - 1, point[1] - 1);
        }
        for (var point : grid(layout.fluidOutCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_OUT)) {
            slotDrawable.draw(graphics, point[0] - 1, point[1] - 1);
        }

        // 分区标题（下行箭头标出输入→产出）
        graphics.drawString(font, "§7输入 §8(物品 9 · 流体 2，不足自动扩)", EDGE, 8, 0xFFFFFF, false);
        graphics.drawString(font, "§7产出 §8(◀ 输入 → 输出；绿=主产物 §d紫=概率产出)", EDGE, 104, 0xFFFFFF, false);
        arrow.draw(graphics, EDGE + 4, 106);

        // 非物品输入槽：**格内右下角**小字号标数量（GT 风格：纯数字 / K·M·G）+ 非消耗标记
        var inSlots = splitInputs(data)[1];
        var fluidInLabelPoints = grid(layout.fluidInCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_IN);
        for (int i = 0; i < Math.min(inSlots.size(), fluidInLabelPoints.size()); i++) {
            var slot = inSlots.get(i);
            if (slot.options().isEmpty()) continue;
            var point = fluidInLabelPoints.get(i);
            drawSlotAmount(graphics, font, slot.options().get(0), point[0], point[1]);
            if (slot.catalyst()) {
                drawSmall(graphics, font, "§e不消耗", point[0], point[1] + 17);
            }
        }

        // 概率产出：在对应槽位下方标 %
        var outSplit = splitOutputs(data);
        var itemOut = outSplit[0];
        var fluidOut = outSplit[1];

        var itemOutPoints = grid(layout.itemOutCells(), ITEMS_PER_ROW, EDGE, Y_ITEM_OUT);
        for (int i = 0; i < Math.min(itemOut.size(), itemOutPoints.size()); i++) {
            float chance = chanceOf(data, itemOut.get(i));
            if (chance < 0f) continue;
            String pct = chance > 0f ? Math.round(chance * 100) + "%" : "?";
            var point = itemOutPoints.get(i);
            graphics.drawString(font, "§d" + pct, point[0], point[1] + 17, 0xFFFFFF, false);
        }
        var fluidOutPoints = grid(layout.fluidOutCells(), FLUIDS_PER_ROW, EDGE, Y_FLUID_OUT);
        for (int i = 0; i < Math.min(fluidOut.size(), fluidOutPoints.size()); i++) {
            var stack = fluidOut.get(i);
            var point = fluidOutPoints.get(i);
            drawSlotAmount(graphics, font, stack, point[0], point[1]);
            float chance = chanceOf(data, stack);
            if (chance >= 0f) {
                String pct = chance > 0f ? Math.round(chance * 100) + "%" : "?";
                graphics.drawString(font, "§d" + pct, point[0], point[1] + 17, 0xFFFFFF, false);
            }
        }

        // 化学物：正常情况已进流体槽渲染（MEK 的 JEI ingredient）；
        // 只有在**渲染不了**时（缺 MEK JEI / Applied-Mekanistics）才退回文字提示
        if (!MekanismJeiCompat.available()) {
            String chems = chemicalSummary(data);
            if (!chems.isEmpty()) {
                graphics.drawString(font, "§c化学物无渲染(缺 MEK JEI/Applied-Mekanistics): " + trim(chems, 44),
                        EDGE, 178, 0xFFFFFF, false);
            }
        }

        // 来源配方 id（带变体标签：序列装配的「步骤 i/N」等）
        String idLine = entry.recipeId() == null ? "" : entry.recipeId();
        if (entry.label() != null && !entry.label().isEmpty()) {
            idLine = entry.label() + "  §8" + idLine;
        }
        if (!idLine.isEmpty()) {
            graphics.drawString(font, "§8" + trim(idLine, 46), EDGE, HEIGHT - 10, 0xFFFFFF, false);
        }

        // 编码按钮
        boolean hover = mouseX >= BTN_X && mouseX <= BTN_X + BTN_W
                && mouseY >= BTN_Y && mouseY <= BTN_Y + BTN_H;
        graphics.fill(BTN_X, BTN_Y, BTN_X + BTN_W, BTN_Y + BTN_H, hover ? 0x8040FF40 : 0x60207020);
        graphics.drawString(font, "§a编码", BTN_X + 2, BTN_Y + 4, 0xFFFFFF, false);
    }

    /** 非物品非流体（化学物）的简要文字摘要（输入 → 输出） */
    private static String chemicalSummary(QianJiPatternData data) {
        var parts = new ArrayList<String>();
        for (QianJiPatternData.Slot slot : data.inputs()) {
            for (GenericStack option : slot.options()) {
                if (isChemical(option)) { parts.add("§e" + label(option)); break; }
            }
        }
        for (QianJiPatternData.Out out : data.primary()) {
            if (isChemical(out.stack())) parts.add("§a" + label(out.stack()));
        }
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            if (isChemical(chanced.stack())) parts.add("§d" + label(chanced.stack()));
        }
        return String.join("§7、", parts);
    }

    private static boolean isChemical(GenericStack stack) {
        return stack != null && stack.what() != null
                && !(stack.what() instanceof AEItemKey) && !(stack.what() instanceof AEFluidKey);
    }

    private static String label(GenericStack stack) {
        String name = stack.what().getDisplayName().getString();
        return stack.amount() > 1 ? name + "×" + stack.amount() : name;
    }

    /**
     * 数量文案（GT 配方界面风格）：**取消单位**，按最小单位（mB / MEK 单位）纯数字记；
     * 过大的数字改用 K / M / G / T / P 计数（1K = 1000）。
     */
    private static String amountLabel(GenericStack stack) {
        long amount = Math.max(1, stack.amount());
        if (amount < 1000) return Long.toString(amount);
        String[] units = {"K", "M", "G", "T", "P"};
        double value = amount;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        String text = scaled(value);
        // 四舍五入后可能又满了 1000（如 999999 → 1000K）→ 再进一位
        if ("1000".equals(text) && unit < units.length - 1) {
            value /= 1000;
            unit++;
            text = scaled(value);
        }
        return text + units[unit];
    }

    /** 三位有效数字、去尾零（1 → "1"，1.5 → "1.5"，12.5 → "12.5"，125 → "125"） */
    private static String scaled(double value) {
        String text = value >= 100
                ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : value >= 10
                ? String.format(java.util.Locale.ROOT, "%.1f", value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    /** 数量画在**格内右下角**（小字号 0.5×，带阴影 — GT 页面的数量就是这个位置） */
    private static void drawSlotAmount(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                       GenericStack stack, int slotX, int slotY) {
        String text = amountLabel(stack);
        graphics.pose().pushPose();
        graphics.pose().translate(slotX + 16f, slotY + 16f, 100f);
        graphics.pose().scale(0.5f, 0.5f, 1f);
        graphics.drawString(font, text, -font.width(text), -font.lineHeight, 0xFFFFFF, true);
        graphics.pose().popPose();
    }

    /** 小字号（0.5×）文字，用于槽位下方的小标注 */
    private static void drawSmall(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                  String text, float x, float y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 100f);
        graphics.pose().scale(0.5f, 0.5f, 1f);
        graphics.drawString(font, text, 0, 0, 0xFFFFFF, false);
        graphics.pose().popPose();
    }

    /** 该产出在数据里是概率产出吗（是则返回其几率，否则 -1） */
    private static float chanceOf(QianJiPatternData data, GenericStack stack) {
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            if (chanced.stack() == stack) return chanced.chance();
        }
        return -1f;
    }

    @Override
    public boolean handleInput(Entry entry, double mouseX, double mouseY,
                               com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (mouseX < BTN_X || mouseX > BTN_X + BTN_W || mouseY < BTN_Y || mouseY > BTN_Y + BTN_H) {
            return false;
        }
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return false;
        }
        AE2Addon.NETWORK.sendToServer(new com.ae2addon.network.QianJiPatternPacket(entry.recipeId(), entry.variant()));
        return true;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
