/*
 * Copyright (C) 2026 CaoBill0522
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.diting.esim;

import android.app.Activity;
import android.app.AlertDialog;
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
                .setPositiveButton(R.string.restart, (d, w) -> apply(enable))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!isChangingConfigurations()) finish();
                })
                .show();
    }

    private void apply(boolean enable) {
        new Thread(() -> {
            if (!EsimController.routeSharedSlot(this, enable)) {
                runOnUiThread(() -> Toast.makeText(this, R.string.switch_failed,
                        Toast.LENGTH_LONG).show());
                return;
            }
            EsimController.setEnabled(enable);
            EsimController.syncSlotOverlay(enable);
            getSystemService(PowerManager.class).reboot(null);
        }).start();
    }
}
