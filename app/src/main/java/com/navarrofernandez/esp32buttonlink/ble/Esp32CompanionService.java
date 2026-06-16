package com.navarrofernandez.esp32buttonlink.ble;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;

public class Esp32CompanionService extends CompanionDeviceService {
    @Override
    public void onDeviceAppeared(String address) {
        BleServiceStarter.startIfReady(this);
    }

    @Override
    public void onDeviceAppeared(AssociationInfo associationInfo) {
        BleServiceStarter.startIfReady(this);
    }
}
