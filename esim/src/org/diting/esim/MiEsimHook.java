/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives the GPIO that wires the second SIM slot to either the eUICC or the SIM2 tray.
 *
 * Mirrors what stock MIUI does on this board (QCMiuiEsimManager.setEsimStateLegacy and
 * com.xiaomi.mirilhook.MiRilHook): Xiaomi "common" messages sent through the QTI OEM hook.
 * QcRilHook ships as a prebuilt shared library, so it is used through reflection.
 */
final class MiEsimHook implements AutoCloseable {
    private static final String TAG = "DitingEsim";

    // QCRIL_EVT_HOOK_BASE + 0x2aa, Xiaomi's common message channel
    private static final int OEM_HOOK_XIAOMI_COMMON = 0x802aa;
    private static final int MSG_GET_ESIM_GPIO = 83;
    private static final int MSG_SET_ESIM_GPIO = 84;
    private static final int MSG_UIM_POWER = 85;

    static final int GPIO_ESIM = 0;
    static final int GPIO_SIM2 = 1;

    // Modem numbering of the slot behind the GPIO
    private static final int SHARED_UIM_SLOT = 2;
    // MiRilHook reserves room for a QcRilHook header ("QOEMHOOK", id, length) after the payload
    private static final int HOOK_HEADER_SIZE = "QOEMHOOK".length() + 8;
    private static final int PHONE_ID = 0;

    private final Object mHook;
    private final Method mSend;
    private final Method mDispose;

    private MiEsimHook(Object hook, Method send, Method dispose) {
        mHook = hook;
        mSend = send;
        mDispose = dispose;
    }

    /** Returns null if the OEM hook service does not come up in time. */
    static MiEsimHook connect(Context context, long timeoutSeconds) {
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> hookClass = cl.loadClass("com.qualcomm.qcrilhook.QcRilHook");
            Class<?> callbackClass = cl.loadClass("com.qualcomm.qcrilhook.QcRilHookCallback");

            CountDownLatch ready = new CountDownLatch(1);
            Object callback = Proxy.newProxyInstance(cl, new Class<?>[] {callbackClass},
                    (proxy, method, args) -> {
                        if ("onQcRilHookReady".equals(method.getName())) ready.countDown();
                        return null;
                    });
            Object hook = hookClass.getConstructor(Context.class, callbackClass)
                    .newInstance(context, callback);
            MiEsimHook result = new MiEsimHook(hook,
                    hookClass.getMethod("sendQcRilHookMsg", int.class, byte[].class, int.class),
                    hookClass.getMethod("dispose"));
            if (!ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
                Log.e(TAG, "QcRilHook did not become ready");
                result.close();
                return null;
            }
            return result;
        } catch (ReflectiveOperationException | InterruptedException e) {
            Log.e(TAG, "QcRilHook is not available", e);
            return null;
        }
    }

    /** Returns GPIO_ESIM, GPIO_SIM2 or -1 if unknown. */
    int getGpio() {
        ByteBuffer response = send(MSG_GET_ESIM_GPIO, 0);
        if (response == null || response.remaining() < 16 || response.getInt() != 0) return -1;
        response.getInt(); // type
        response.getInt(); // length
        return response.getInt();
    }

    /** Sets the GPIO level and persists it in the modem EFS, like stock does. */
    boolean setGpio(int level) {
        return isOk(send(MSG_SET_ESIM_GPIO, 8, level, 1 /* update_efs */));
    }

    boolean setUimPower(boolean on, int ignoreHotswap) {
        return isOk(send(MSG_UIM_POWER, on ? 1 : 0, SHARED_UIM_SLOT, ignoreHotswap));
    }

    private static boolean isOk(ByteBuffer response) {
        return response != null && response.remaining() >= 4 && response.getInt() == 0;
    }

    private ByteBuffer send(int... values) {
        byte[] request = new byte[values.length * 4 + HOOK_HEADER_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(request).order(ByteOrder.nativeOrder());
        for (int value : values) buffer.putInt(value);
        try {
            Object ar = mSend.invoke(mHook, OEM_HOOK_XIAOMI_COMMON, request, PHONE_ID);
            if (ar == null) return null;
            Throwable exception = (Throwable) ar.getClass().getField("exception").get(ar);
            Object result = ar.getClass().getField("result").get(ar);
            if (exception != null || !(result instanceof byte[])) {
                Log.e(TAG, "Hook message " + values[0] + " failed", exception);
                return null;
            }
            return ByteBuffer.wrap((byte[]) result).order(ByteOrder.nativeOrder());
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Hook message " + values[0] + " failed", e);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            mDispose.invoke(mHook);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Failed to dispose QcRilHook", e);
        }
    }
}
