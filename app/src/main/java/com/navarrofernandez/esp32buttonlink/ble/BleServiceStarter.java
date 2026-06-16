package com.navarrofernandez.esp32buttonlink.ble;

import android.Manifest;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class BleServiceStarter {
    private BleServiceStarter() {
    }

    public static boolean canStart(Context context) {
        if (new BleSettingsRepository(context).deviceAddress().isEmpty()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean startIfReady(Context context) {
        if (!canStart(context)) {
            return false;
        }
        Intent intent = new Intent(context, BleTriggerService.class);
        intent.setAction(BleTriggerService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException | SecurityException error) {
            return false;
        }
        return true;
    }

    public static void observePresenceIfPossible(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String address = new BleSettingsRepository(context).deviceAddress();
        if (address.isEmpty()) {
            return;
        }
        CompanionDeviceManager manager =
                (CompanionDeviceManager) context.getSystemService(Context.COMPANION_DEVICE_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.startObservingDevicePresence(address);
        } catch (Exception ignored) {
            // Presence observation only works for devices associated with this app.
        }
    }
}
