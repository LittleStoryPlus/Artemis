/*
 * Copyright © Wynntils 2022.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.user.chat;

import com.wynntils.core.components.Models;
import com.wynntils.core.features.UserFeature;
import com.wynntils.core.features.properties.FeatureCategory;
import com.wynntils.core.features.properties.FeatureInfo;
import com.wynntils.handlers.chat.event.ChatMessageReceivedEvent;
import com.wynntils.mc.event.ChatSentEvent;
import com.wynntils.mc.event.ScreenOpenedEvent;
import com.wynntils.utils.mc.McUtils;
import java.util.regex.Pattern;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@FeatureInfo(category = FeatureCategory.CHAT)
public class TradeMarketPriceConversionFeature extends UserFeature {

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("^§6Type the price in emeralds or type 'cancel' to cancel:$");
    private static final Pattern TRADE_MARKET_PATTERN = Pattern.compile("^What would you like to sell\\?$");
    private static final Pattern CANCELLED_PATTERN = Pattern.compile("^You moved and your chat input was canceled.$");

    private boolean shouldConvert = false;

    @SubscribeEvent
    public void onChatMessageReceive(ChatMessageReceivedEvent event) {
        if (PRICE_PATTERN.matcher(event.getOriginalCodedMessage()).matches()) {
            shouldConvert = true;
        }
        if (CANCELLED_PATTERN.matcher(event.getOriginalCodedMessage()).matches()) {
            shouldConvert = false;
        }
    }

    @SubscribeEvent
    public void onClientChat(ChatSentEvent event) {
        if (!shouldConvert) return;
        shouldConvert = false;

        String price = Models.Emerald.convertEmeraldPrice(event.getMessage());
        if (!price.isEmpty()) {
            event.setCanceled(true);
            McUtils.mc().getConnection().sendChat(price);
        }
    }

    @SubscribeEvent
    public void onGuiOpen(ScreenOpenedEvent.Post event) {
        if (TRADE_MARKET_PATTERN
                .matcher(event.getScreen().getTitle().getString())
                .matches()) {
            shouldConvert = false;
        }
    }
}