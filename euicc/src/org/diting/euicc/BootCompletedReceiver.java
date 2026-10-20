/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.euicc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Hides OpenEUICC on boards without an eUICC.
 *
 * The eUICC feature is only declared for the ditingp SKU (see device.mk), so
 * the feature check below is the single source of truth for eSIM support.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "DitingEuicc";
    private static final String LPA_PACKAGE = "im.angry.openeuicc";

    @Override
    public void onReceive(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        boolean supported = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
        int wanted = supported
                ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        try {
            if (pm.getApplicationEnabledSetting(LPA_PACKAGE) != wanted) {
                pm.setApplicationEnabledSetting(LPA_PACKAGE, wanted, 0);
                Log.i(TAG, (supported ? "Enabled " : "Disabled ") + LPA_PACKAGE);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, LPA_PACKAGE + " is not installed");
        }
    }
}
