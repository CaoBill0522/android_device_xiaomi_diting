/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.qti.extphone.Client;
import com.qti.extphone.ExtPhoneCallbackListener;
import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.QtiSimType;
import com.qti.extphone.ServiceCallback;
import com.qti.extphone.Status;
import com.qti.extphone.Token;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eSIM switch shared by Settings (international board) and JcaoParts (forced on other boards).
 *
 * persist.sys.jcao.esim decides at boot whether the eUICC feature is declared (SystemConfig)
 * and whether the slot overlay applies. On the ditingp board the second slot is also wired
 * through a GPIO to either the eUICC or the SIM2 tray, switched with QTI's setSimType like
 * stock MIUI does.
 */
final class EsimController {
    private static final String TAG = "DitingEsim";

    static final String PROP_ESIM = "persist.sys.jcao.esim";
    private static final String PROP_SKU = "ro.boot.product.hardware.sku";
    private static final String SKU_WITH_EUICC = "ditingp";

    private static final String LPA_PACKAGE = "im.angry.openeuicc";

    private static final int SHARED_SLOT = 1;
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

        ExtTelephonyManager etm = ExtTelephonyManager.getInstance(context);
        CountDownLatch connected = new CountDownLatch(1);
        ServiceCallback serviceCallback = new ServiceCallback() {
            @Override
            public void onConnected() {
                connected.countDown();
            }

            @Override
            public void onDisconnected() {}
        };

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        ExtPhoneCallbackListener listener = null;
        try {
            if (!etm.isServiceConnected()) {
                etm.connectService(serviceCallback);
                if (!connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.e(TAG, "ExtTelephonyService did not connect");
                    return false;
                }
            }

            int want = esim ? QtiSimType.SIM_TYPE_ESIM : QtiSimType.SIM_TYPE_PHYSICAL;
            QtiSimType[] current = etm.getCurrentSimType();
            if (current != null && current.length > SHARED_SLOT
                    && current[SHARED_SLOT].get() == want) {
                return true;
            }

            CountDownLatch responded = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            listener = new ExtPhoneCallbackListener(thread.getLooper()) {
                @Override
                public void setSimTypeResponse(Token token, Status status) {
                    success.set(status != null && status.get() == Status.SUCCESS);
                    responded.countDown();
                }
            };
            Client client = etm.registerCallbackWithEvents(context.getPackageName(), listener,
                    new int[] {ExtPhoneCallbackListener.EVENT_SET_SIM_TYPE_RESPONSE});
            if (client == null) {
                Log.e(TAG, "Failed to register with ExtTelephonyService");
                return false;
            }

            int first = current != null && current.length > 0
                    ? current[0].get() : QtiSimType.SIM_TYPE_PHYSICAL;
            QtiSimType[] types = {new QtiSimType(first), new QtiSimType(want)};
            if (etm.setSimType(client, types) == null) {
                Log.e(TAG, "setSimType was not sent");
                return false;
            }
            if (!responded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.e(TAG, "setSimType timed out");
                return false;
            }
            Log.i(TAG, "Routed slot " + SHARED_SLOT + " to " + (esim ? "eSIM" : "SIM2")
                    + ": " + success.get());
            return success.get();
        } catch (RemoteException | InterruptedException e) {
            Log.e(TAG, "Failed to route the shared slot", e);
            return false;
        } finally {
            if (listener != null) etm.unregisterCallback(listener);
            etm.disconnectService(serviceCallback);
            thread.quitSafely();
        }
    }
}
