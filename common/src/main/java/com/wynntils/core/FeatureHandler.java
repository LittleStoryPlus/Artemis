/*
 * Copyright © Wynntils 2021.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.core;

import com.wynntils.core.features.Feature;
import java.util.LinkedList;
import java.util.List;

public class FeatureHandler {
    private static final List<Feature> features = new LinkedList<>();

    public static void registerFeature(Feature feature) {
        features.add(feature);
    }

    public static void unregisterFeature(Feature feature) {
        features.remove(feature);
    }

    public static void initalizeFeatures() {
        features.forEach(Feature::onEnable);
    }
}