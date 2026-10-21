/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        EsimController.syncLpa(context);

        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !EsimController.hasEuiccSlot()) {
            return;
        }
        // The modem remembers the last slot routing, which may come from another ROM
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                EsimController.routeSharedSlot(context, EsimController.isEnabled());
            } finally {
                result.finish();
            }
        }).start();
    }
}
