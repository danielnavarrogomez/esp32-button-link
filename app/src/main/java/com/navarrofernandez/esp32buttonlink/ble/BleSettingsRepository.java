package com.navarrofernandez.esp32buttonlink.ble;

import android.content.Context;
import android.content.SharedPreferences;

public class BleSettingsRepository {
    private static final String PREFS_NAME = "esp32_button_link_ble";
    private static final String KEY_DEVICE_ADDRESS = "device_address";
    private static final String KEY_DEVICE_NAME = "device_name";

    private final SharedPreferences preferences;

    public BleSettingsRepository(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String deviceAddress() {
        return preferences.getString(KEY_DEVICE_ADDRESS, "");
    }

    public String deviceName() {
        return preferences.getString(KEY_DEVICE_NAME, "");
    }

    public void saveDevice(String address, String name) {
        preferences.edit()
                .putString(KEY_DEVICE_ADDRESS, address == null ? "" : address)
                .putString(KEY_DEVICE_NAME, name == null ? "" : name)
                .apply();
    }
}
