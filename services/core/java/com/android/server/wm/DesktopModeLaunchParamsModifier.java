/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static android.util.DisplayMetrics.DENSITY_DEFAULT;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
import com.android.wm.shell.Flags;

/**
 * The class that defines default launch params for tasks in desktop mode
 */
public class DesktopModeLaunchParamsModifier implements LaunchParamsModifier {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "DesktopModeLaunchParamsModifier" : TAG_ATM;
    private static final boolean DEBUG = false;

    // Desktop mode feature flags.
    private static final boolean ENABLE_DESKTOP_WINDOWING = Flags.enableDesktopWindowing();
    private static final boolean DESKTOP_MODE_PROTO2_SUPPORTED =
            SystemProperties.getBoolean("persist.wm.debug.desktop_mode_2", false);
    // Override default freeform task width when desktop mode is enabled. In dips.
    private static final int DESKTOP_MODE_DEFAULT_WIDTH_DP = SystemProperties.getInt(
            "persist.wm.debug.desktop_mode.default_width", 840);
    // Override default freeform task height when desktop mode is enabled. In dips.
    private static final int DESKTOP_MODE_DEFAULT_HEIGHT_DP = SystemProperties.getInt(
            "persist.wm.debug.desktop_mode.default_height", 630);

    private StringBuilder mLogBuilder;
    private static final int OFFSET_X_DP = 100;
    private static final int OFFSET_Y_DP = 100;

    private int mLastOffsetX = 0;
    private int mLastOffsetY = 0;

    @Override
    public int onCalculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {

        initLogBuilder(task, activity);
        int result = calculate(task, layout, activity, source, options, request, phase,
                currentParams, outParams);
        outputLog();
        return result;
    }

    private int calculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {

        if (task == null) {
            appendLog("task null, skipping");
            return RESULT_SKIP;
        }
        if (!task.isActivityTypeStandardOrUndefined()) {
            appendLog("not standard or undefined activity type, skipping");
            return RESULT_SKIP;
        }
        if (phase < PHASE_WINDOWING_MODE) {
            appendLog("not in windowing mode or bounds phase, skipping");
            return RESULT_SKIP;
        }

        // Copy over any values
        outParams.set(currentParams);

        // In Proto2, trampoline task launches of an existing background task can result in the
        // previous windowing mode to be restored even if the desktop mode state has changed.
        // Let task launches inherit the windowing mode from the source task if available, which
        // should have the desired windowing mode set by WM Shell. See b/286929122.
        if (isDesktopModeSupported() && source != null && source.getTask() != null) {
            final Task sourceTask = source.getTask();
            outParams.mWindowingMode = sourceTask.getWindowingMode();
            appendLog("inherit-from-source=" + outParams.mWindowingMode);
        }

        if (phase == PHASE_WINDOWING_MODE) {
            return RESULT_DONE;
        }

        if (!currentParams.mBounds.isEmpty()) {
            appendLog("currentParams has bounds set, not overriding");
            return RESULT_SKIP;
        }

        // Update width and height with default desktop mode values
        float density = (float) task.getConfiguration().densityDpi / DENSITY_DEFAULT;
        final int width = (int) (DESKTOP_MODE_DEFAULT_WIDTH_DP * density + 0.5f);
        final int height = (int) (DESKTOP_MODE_DEFAULT_HEIGHT_DP * density + 0.5f);
        outParams.mBounds.right = width;
        outParams.mBounds.bottom = height;

        // Stagger the window position 
        outParams.mBounds.offset(mLastOffsetX, mLastOffsetY);
        
        mLastOffsetX += (int) (OFFSET_X_DP * density + 0.5f);
        mLastOffsetY += (int) (OFFSET_Y_DP * density + 0.5f);

        Rect windowBounds = task.getWindowConfiguration().getBounds();
        if (outParams.mBounds.right > windowBounds.right) {
            mLastOffsetX = 0;
        }
        if (outParams.mBounds.bottom > windowBounds.bottom) {
            mLastOffsetY = 0;
        }

        appendLog("setting desktop mode task bounds to %s", outParams.mBounds);

        return RESULT_DONE;
    }

    private void initLogBuilder(Task task, ActivityRecord activity) {
        if (DEBUG) {
            mLogBuilder = new StringBuilder(
                    "DesktopModeLaunchParamsModifier: task=" + task + " activity=" + activity);
        }
    }

    private void appendLog(String format, Object... args) {
        if (DEBUG) mLogBuilder.append(" ").append(String.format(format, args));
    }

    private void outputLog() {
        if (DEBUG) Slog.d(TAG, mLogBuilder.toString());
    }

    /** Whether desktop mode is supported. */
    static boolean isDesktopModeSupported() {
        // Check for aconfig flag first
        if (ENABLE_DESKTOP_WINDOWING) {
            return true;
        }
        // Fall back to sysprop flag
        // TODO(b/304778354): remove sysprop once desktop aconfig flag supports dynamic overriding
        return DESKTOP_MODE_PROTO2_SUPPORTED;
    }
}
