/*
 * Copyright © Wynntils 2022.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.overlays;

import com.wynntils.core.config.Category;
import com.wynntils.core.config.Config;
import com.wynntils.core.config.ConfigCategory;
import com.wynntils.core.features.UserFeature;
import com.wynntils.core.features.overlays.RenderState;
import com.wynntils.core.features.overlays.TextOverlay;
import com.wynntils.core.features.overlays.annotations.OverlayGroup;
import com.wynntils.mc.event.RenderEvent;
import java.util.ArrayList;
import java.util.List;

@ConfigCategory(Category.OVERLAYS)
public class InfoBoxFeature extends UserFeature {
    @OverlayGroup(instances = 7, renderType = RenderEvent.ElementType.GUI, renderAt = RenderState.Pre)
    private final List<InfoBoxOverlay> infoBoxOverlays = new ArrayList<>();

    public static class InfoBoxOverlay extends TextOverlay {
        @Config
        public String content = "";

        public InfoBoxOverlay(int id) {
            super(id);
        }

        @Override
        public String getTemplate() {
            return content;
        }

        @Override
        public String getPreviewTemplate() {
            return "&cX: {x:0}, &9Y: {y:0}, &aZ: {z:0}";
        }
    }
}