/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.util.Log;

/**
 * eSIM switch shared by Settings (international board) and JcaoParts (forced on other boards).
 *
 * persist.sys.jcao.esim decides at boot whether the eUICC feature is declared (SystemConfig)
 * and whether the slot overlay applies. On the ditingp board the second slot is also wired
 * through a GPIO to either the eUICC or the SIM2 tray, switched through Xiaomi's OEM hook
 * messages like stock MIUI does (see MiEsimHook).
 */
final class EsimController {
    private static final String TAG = "DitingEsim";

    static final String PROP_ESIM = "persist.sys.jcao.esim";
    private static final String PROP_SKU = "ro.boot.product.hardware.sku";
    private static final String SKU_WITH_EUICC = "ditingp";

    private static final String LPA_PACKAGE = "im.angry.openeuicc";

    private static final long TIMEOUT_SECONDS = 5;

    private EsimController() {}

    static boolean hasEuiccSlot() {
        return SKU_WITH_EUICC.equals(SystemProperties.get(PROP_SKU));
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ESIM, false);
    }

    static void setEnabled(boolean enabled) {
        SystemProperties.set(PROP_ESIM, enabled ? "1" : "0");
    }

    /** Shows OpenEUICC only while the system declares the eUICC feature. */
    static void syncLpa(Context context) {
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

    /**
     * Routes the shared slot to the eUICC or to the SIM2 tray. Blocks for up to a few seconds,
     * so never call it on the main thread. Returns true if the slot already is or now is routed
     * as requested.
     */
    static boolean routeSharedSlot(Context context, boolean esim) {
        if (!hasEuiccSlot()) return true;

        try (MiEsimHook hook = MiEsimHook.connect(context, TIMEOUT_SECONDS)) {
            if (hook == null) return false;

            int want = esim ? MiEsimHook.GPIO_ESIM : MiEsimHook.GPIO_SIM2;
            int current = hook.getGpio();
            if (current == want) return true;

            // Same sequence as stock: power the slot down, flip the GPIO, power it back up
            if (!hook.setUimPower(false, -1)) {
                Log.w(TAG, "Failed to power down the shared slot");
            }
            boolean switched = hook.setGpio(want);
            if (!hook.setUimPower(true, esim ? 1 : 0)) {
                Log.w(TAG, "Failed to power up the shared slot");
            }
            Log.i(TAG, "Routed the shared slot from " + current + " to "
                    + (esim ? "eSIM" : "SIM2") + ": " + switched);
            return switched;
        }
    }
}
