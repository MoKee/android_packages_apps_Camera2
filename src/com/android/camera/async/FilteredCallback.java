/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.camera.async;

import com.android.camera.util.Callback;
import com.google.common.base.Objects;

import javax.annotation.Nonnull;

/**
 * Wraps a callback to filter out duplicate updates.
 */
public class FilteredCallback<T> implements Callback<T> {
    private final Callback<? super T> mCallback;
    private T mLatestValue;

    public FilteredCallback(Callback<? super T> callback) {
        mCallback = callback;
        mLatestValue = null;
    }

    @Override
    public void onCallback(@Nonnull T newValue) {
        if (!Objects.equal(mLatestValue, newValue)) {
            mLatestValue = newValue;
            mCallback.onCallback(newValue);
        }
    }
}