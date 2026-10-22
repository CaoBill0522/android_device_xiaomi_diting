/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * Turns eSIM on or off after the user confirms, then reboots so SystemConfig and the slot
 * overlay pick up the new state. Callers pass {@link #EXTRA_ENABLED}.
 */
public class EsimSwitchActivity extends Activity {
    public static final String EXTRA_ENABLED = "enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!getIntent().hasExtra(EXTRA_ENABLED)) {
            finish();
            return;
        }
        boolean enable = getIntent().getBooleanExtra(EXTRA_ENABLED, false);
        if (enable == EsimController.isEnabled()) {
            finish();
            return;
        }

        boolean sharedSlot = EsimController.hasEuiccSlot();
        int message = !sharedSlot ? R.string.switch_message
                : enable ? R.string.switch_on_message_shared_slot
                : R.string.switch_off_message_shared_slot;
        new AlertDialog.Builder(this)
                .setTitle(enable ? R.string.switch_on_title : R.string.switch_off_title)
                .setMessage(message)
                .setPositiveButton(sharedSlot ? R.string.switch_now : R.string.restart,
                        (d, w) -> apply(enable, sharedSlot))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!isChangingConfigurations()) finish();
                })
                .show();
    }

    private void apply(boolean enable, boolean sharedSlot) {
        // The dialog finishes this activity before the switch is done
        Context app = getApplicationContext();
        new Thread(() -> {
            if (!EsimController.routeSharedSlot(app, enable)) {
                app.getMainExecutor().execute(() -> Toast.makeText(app,
                        R.string.switch_failed, Toast.LENGTH_LONG).show());
                return;
            }
            EsimController.setEnabled(enable);
            if (!sharedSlot) {
                // Without a built-in eUICC the eSIM feature itself is toggled, which
                // SystemConfig only reads at boot
                app.getSystemService(PowerManager.class).reboot(null);
                return;
            }
            // The shared slot was power-cycled while routing; once the card is back,
            // let telephony pick up the embedded profiles (or drop them) right away.
            EsimController.refreshEmbeddedSubscriptions(app);
            app.getMainExecutor().execute(() -> Toast.makeText(app,
                    enable ? R.string.switched_on : R.string.switched_off,
                    Toast.LENGTH_SHORT).show());
        }).start();
    }
}
