/*
 * Copyright © Wynntils 2022-2023.
 * This file is released under LGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.tooltips;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Models;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.persisted.Persisted;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.Config;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.handlers.tooltip.TooltipBuilder;
import com.wynntils.handlers.tooltip.gear.IdentifiableGearItemInfo;
import com.wynntils.handlers.tooltip.tome.IdentifiableTomeItemInfo;
import com.wynntils.handlers.tooltip.type.IdentifiableItemInfo;
import com.wynntils.handlers.tooltip.type.TooltipIdentificationDecorator;
import com.wynntils.handlers.tooltip.type.TooltipStyle;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.models.items.WynnItem;
import com.wynntils.models.items.WynnItemData;
import com.wynntils.models.items.items.game.GearItem;
import com.wynntils.models.items.items.game.TomeItem;
import com.wynntils.models.stats.StatCalculator;
import com.wynntils.models.stats.type.StatActualValue;
import com.wynntils.models.stats.type.StatListOrdering;
import com.wynntils.models.stats.type.StatPossibleValues;
import com.wynntils.utils.mc.ComponentUtils;
import com.wynntils.utils.mc.KeyboardUtils;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.type.Pair;
import com.wynntils.utils.wynn.ColorScaleUtils;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

@ConfigCategory(Category.TOOLTIPS)
public class ItemStatInfoFeature extends Feature {
    private static final Function<GearItem, IdentifiableItemInfo> GEAR_ITEM_INFO_FUNCTION =
            gearItem -> IdentifiableGearItemInfo.from(
                    gearItem.getGearInfo(), gearItem.getGearInstance().orElse(null));

    private static final Function<TomeItem, IdentifiableItemInfo> TOME_ITEM_INFO_FUNCTION =
            tomeItem -> IdentifiableTomeItemInfo.from(
                    tomeItem.getTomeInfo(), tomeItem.getTomeInstance().orElse(null));

    private final Set<WynnItem> brokenItems = new HashSet<>();

    @Persisted
    public final Config<Boolean> showStars = new Config<>(true);

    @Persisted
    public final Config<Boolean> colorLerp = new Config<>(true);

    @Persisted
    public final Config<Integer> decimalPlaces = new Config<>(1);

    @Persisted
    public final Config<Boolean> perfect = new Config<>(true);

    @Persisted
    public final Config<Boolean> defective = new Config<>(true);

    @Persisted
    public final Config<Float> obfuscationChanceStart = new Config<>(0.08f);

    @Persisted
    public final Config<Float> obfuscationChanceEnd = new Config<>(0.04f);

    @Persisted
    public final Config<StatListOrdering> identificationsOrdering = new Config<>(StatListOrdering.DEFAULT);

    @Persisted
    public final Config<Boolean> groupIdentifications = new Config<>(true);

    @Persisted
    public final Config<Boolean> identificationDecorations = new Config<>(true);

    @Persisted
    public final Config<Boolean> overallPercentageInName = new Config<>(true);

    @Persisted
    public final Config<Boolean> showBestValueLastAlways = new Config<>(true);

    @SubscribeEvent
    public void onTooltipPre(ItemTooltipRenderEvent.Pre event) {
        if (KeyboardUtils.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT)) return;

        Optional<WynnItem> wynnItemOpt = Models.Item.getWynnItem(event.getItemStack());
        if (wynnItemOpt.isEmpty()) return;

        WynnItem wynnItem = wynnItemOpt.get();
        if (brokenItems.contains(wynnItem)) return;

        IdentifiableItemInfo itemInfo = getItemInfo(wynnItem);
        if (itemInfo == null) return;

        try {
            TooltipBuilder builder = wynnItem.getData()
                    .getOrCalculate(
                            WynnItemData.TOOLTIP_KEY,
                            () -> Handlers.Tooltip.fromParsedItemStack(event.getItemStack(), itemInfo));
            if (builder == null) return;

            IdentificationDecorator decorator = identificationDecorations.get() ? new IdentificationDecorator() : null;
            TooltipStyle currentIdentificationStyle = new TooltipStyle(
                    identificationsOrdering.get(),
                    groupIdentifications.get(),
                    showBestValueLastAlways.get(),
                    showStars.get());
            LinkedList<Component> tooltips = new LinkedList<>(
                    builder.getTooltipLines(Models.Character.getClassType(), currentIdentificationStyle, decorator));

            // Update name depending on overall percentage; this needs to be done every rendering
            // for rainbow/defective effects
            if (overallPercentageInName.get() && itemInfo.hasOverallValue()) {
                updateItemName(itemInfo, tooltips);
            }

            event.setTooltips(tooltips);
        } catch (Exception e) {
            brokenItems.add(wynnItem);
            WynntilsMod.error("Exception when creating tooltips for item " + itemInfo.getName(), e);
            WynntilsMod.warn("This item has been disabled from ItemStatInfoFeature: " + wynnItem);
            McUtils.sendErrorToClient("Wynntils error: Problem showing tooltip for item " + itemInfo.getName());

            if (brokenItems.size() > 10) {
                // Give up and disable feature
                throw new RuntimeException(e);
            }
        }
    }

    private IdentifiableItemInfo getItemInfo(WynnItem wynnItem) {
        if (wynnItem instanceof GearItem gearItem) {
            return GEAR_ITEM_INFO_FUNCTION.apply(gearItem);
        }
        if (wynnItem instanceof TomeItem tomeItem) {
            return TOME_ITEM_INFO_FUNCTION.apply(tomeItem);
        }

        return null;
    }

    private void updateItemName(IdentifiableItemInfo itemInfo, Deque<Component> tooltips) {
        MutableComponent name;
        if (perfect.get() && itemInfo.isPerfect()) {
            name = ComponentUtils.makeRainbowStyle("Perfect " + itemInfo.getName());
        } else if (defective.get() && itemInfo.isDefective()) {
            name = ComponentUtils.makeObfuscated(
                    "Defective " + itemInfo.getName(), obfuscationChanceStart.get(), obfuscationChanceEnd.get());
        } else {
            name = tooltips.getFirst().copy();
            name.append(ColorScaleUtils.getPercentageTextComponent(
                    itemInfo.getOverallPercentage(), colorLerp.get(), decimalPlaces.get()));
        }
        tooltips.removeFirst();
        tooltips.addFirst(name);
    }

    private class IdentificationDecorator implements TooltipIdentificationDecorator {
        @Override
        public MutableComponent getSuffix(
                StatActualValue statActualValue, StatPossibleValues possibleValues, TooltipStyle style) {
            if (!possibleValues.range().inRange(statActualValue.value())) {
                // Our actual value lies outside the range of possible values
                // This can happen if the API data is outdated. In this case, just mark
                // the stat as "NEW".
                return Component.literal(" [NEW]").withStyle(ChatFormatting.GOLD);
            }

            if (KeyboardUtils.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
                    && KeyboardUtils.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL)) {
                return getInnerRollSuffix(style, statActualValue, possibleValues);
            } else if (KeyboardUtils.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) {
                return getRangeSuffix(style, statActualValue, possibleValues);
            } else if (KeyboardUtils.isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL)) {
                return getRerollSuffix(style, statActualValue, possibleValues);
            } else {
                return getPercentSuffix(style, statActualValue, possibleValues);
            }
        }

        private MutableComponent getInnerRollSuffix(
                TooltipStyle style, StatActualValue statActualValue, StatPossibleValues possibleValues) {
            MutableComponent rangeTextComponent = Component.literal(" <")
                    .append(Component.literal(statActualValue.internalRoll().low() + "% to "
                                    + statActualValue.internalRoll().high() + "%")
                            .withStyle(ChatFormatting.GREEN))
                    .append(">")
                    .withStyle(ChatFormatting.DARK_GREEN);

            return rangeTextComponent;
        }

        private MutableComponent getRangeSuffix(
                TooltipStyle style, StatActualValue actualValue, StatPossibleValues possibleValues) {
            Pair<Integer, Integer> displayRange =
                    StatCalculator.getDisplayRange(possibleValues, style.showBestValueLastAlways());

            MutableComponent rangeTextComponent = Component.literal(" [")
                    .append(Component.literal(displayRange.a() + ", " + displayRange.b())
                            .withStyle(ChatFormatting.GREEN))
                    .append("]")
                    .withStyle(ChatFormatting.DARK_GREEN);

            return rangeTextComponent;
        }

        private MutableComponent getRerollSuffix(
                TooltipStyle style, StatActualValue actualValue, StatPossibleValues possibleValues) {
            MutableComponent rerollChancesComponent = Component.literal(String.format(
                            Locale.ROOT, " \u2605%.2f%%", StatCalculator.getPerfectChance(possibleValues)))
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(String.format(
                                    Locale.ROOT,
                                    " \u21E7%.1f%%",
                                    StatCalculator.getIncreaseChance(actualValue, possibleValues)))
                            .withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(String.format(
                                    Locale.ROOT,
                                    " \u21E9%.1f%%",
                                    StatCalculator.getDecreaseChance(actualValue, possibleValues)))
                            .withStyle(ChatFormatting.RED));

            return rerollChancesComponent;
        }

        private MutableComponent getPercentSuffix(
                TooltipStyle style, StatActualValue actualValue, StatPossibleValues possibleValues) {
            float percentage = StatCalculator.getPercentage(actualValue, possibleValues);
            MutableComponent percentageTextComponent =
                    ColorScaleUtils.getPercentageTextComponent(percentage, colorLerp.get(), decimalPlaces.get());

            return percentageTextComponent;
        }
    }
}
