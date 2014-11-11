/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.one;

/**
 * Contains 3A parameters common to all camera flavors.
 * TODO: Move to GservicesHelper.
 */
public class Settings3A {

    /**
     * Width of touch AF region in [0,1] relative to shorter edge of the current
     * crop region. Multiply this number by the number of pixels along the
     * shorter edge of the current crop region's width to get a value in pixels.
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device depending on how its ISP interprets the metering box and weight.
     * </p>
     *
     * <p>
     * Values prior to L release:
     * Normal mode: 0.125 * longest edge
     * Gcam: Fixed at 300px x 300px.
     * </p>
     */
    private static final float AF_REGION_BOX = 0.2f;

    /**
     * Width of touch metering region in [0,1] relative to shorter edge of the
     * current crop region. Multiply this number by the number of pixels along
     * shorter edge of the current crop region's width to get a value in pixels.
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device depending on how its ISP interprets the metering box and weight.
     * </p>
     *
     * <p>
     * Values prior to L release:
     * Normal mode: 0.1875 * longest edge
     * Gcam: Fixed at 300px x 300px.
     * </p>
     */
    private static final float AE_REGION_BOX = 0.3f;

    /** Metering region weight between 0 and 1.
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device depending on how its ISP interprets the metering box and weight.
     * </p>
     */
    private static final float REGION_WEIGHT = 0.022f;

    /** Duration to hold after manual tap to focus. */
    private static final int FOCUS_HOLD_MILLIS = 3000;

    /**
     * Width of touch metering region in [0,1] relative to shorter edge of the
     * current crop region. Multiply this number by the number of pixels along
     * shorter edge of the current crop region's width to get a value in pixels.
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device depending on how its ISP interprets the metering box and weight.
     * </p>
     *
     * <p>
     * Was fixed at 300px x 300px prior to L release.
     * </p>
     */
    private static final float GCAM_METERING_REGION_FRACTION = 0.1225f;

    /**
     * Weight of a touch metering region, in [0, \inf).
     *
     * <p>
     * This value has been tested on Nexus 5 and Shamu, but will need to be
     * tuned per device.
     * </p>
     *
     * <p>
     * Was fixed at 15.0f prior to L release.
     * </p>
     */
    private static final float GCAM_METERING_REGION_WEIGHT = 22.0f;

    public static float getAutoFocusRegionWidth() {
        return AF_REGION_BOX;
    }

    public static float getMeteringRegionWidth() {
        return AE_REGION_BOX;
    }

    public static float getMeteringRegionWeight() {
        return REGION_WEIGHT;
    }

    public static float getGcamMeteringRegionFraction() {
        return GCAM_METERING_REGION_FRACTION;
    }

    public static float getGcamMeteringRegionWeight() {
        return GCAM_METERING_REGION_WEIGHT;
    }

    public static int getFocusHoldMillis() {
        return FOCUS_HOLD_MILLIS;
    }
}
