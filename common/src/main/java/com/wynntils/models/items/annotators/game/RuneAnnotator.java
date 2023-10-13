/*
 * Copyright © Wynntils 2023.
 * This file is released under LGPLv3. See LICENSE for full license details.
 */
package com.wynntils.models.items.annotators.game;

import com.wynntils.core.text.StyledText;
import com.wynntils.models.items.items.game.GameItem;
import com.wynntils.models.items.items.game.RuneItem;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.world.item.ItemStack;

public final class RuneAnnotator extends GameItemAnnotator {
    // https://regexr.com/7lhjd
    private static final Pattern RUNE_PATTERN = Pattern.compile("§[b432]([A-Z][a-z]{1,2}) Rune");

    @Override
    public GameItem getAnnotation(ItemStack itemStack, StyledText name, int emeraldPrice) {
        Matcher m = name.getMatcher(RUNE_PATTERN);
        if (!m.matches()) return null;

        return new RuneItem(emeraldPrice, RuneItem.RuneType.valueOf(m.group(1).toUpperCase(Locale.ROOT)));
    }
}
