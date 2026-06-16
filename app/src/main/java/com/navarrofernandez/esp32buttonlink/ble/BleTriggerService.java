package com.navarrofernandez.esp32buttonlink.ble;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.navarrofernandez.esp32buttonlink.R;
import com.navarrofernandez.esp32buttonlink.data.EndpointConfig;
import com.navarrofernandez.esp32buttonlink.data.EndpointRepository;
import com.navarrofernandez.esp32buttonlink.net.EndpointCaller;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BleTriggerService extends Service {
    public static final String ACTION_START = "com.navarrofernandez.esp32buttonlink.ble.START";
    public static final String ACTION_STOP = "com.navarrofernandez.esp32buttonlink.ble.STOP";

    private static final String TAG = "ESP32ButtonLinkBle";
    private static final String CHANNEL_ID = "esp32_button_link_ble";
    private static final int NOTIFICATION_ID = 42;
    private static final UUID SERVICE_UUID = UUID.fromString("8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a001");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a002");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BluetoothGatt gatt;
    private String deviceAddress = "";
    private boolean running = false;
    private boolean reconnectScheduled = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopListening();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        startForeground(NOTIFICATION_ID, buildNotification("Listening for ESP32 trigger"));
        deviceAddress = new BleSettingsRepository(this).deviceAddress();
        connect();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopListening();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void connect() {
        if (!hasConnectPermission() || deviceAddress.isEmpty()) {
            Log.w(TAG, "Missing BLE permission or selected device");
            return;
        }

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is unavailable or disabled");
            scheduleReconnect(10000);
            return;
        }

        try {
            reconnectScheduled = false;
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            closeGatt();
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (IllegalArgumentException error) {
            Log.e(TAG, "Invalid BLE device address", error);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (!hasConnectPermission()) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE connected");
                if (!bluetoothGatt.discoverServices()) {
                    scheduleReconnect(3000);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE disconnected");
                closeGatt();
                if (running) {
                    scheduleReconnect(3000);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (!hasConnectPermission()) {
                return;
            }
            BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
            BluetoothGattCharacteristic characteristic =
                    service == null ? null : service.getCharacteristic(CHARACTERISTIC_UUID);
            if (characteristic == null) {
                Log.w(TAG, "ESP32 trigger characteristic not found");
                closeGatt();
                scheduleReconnect(5000);
                return;
            }

            bluetoothGatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothGatt.writeDescriptor(descriptor);
            }
            Log.i(TAG, "BLE notifications enabled");
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic.getValue());
        }
    };

    private void handleNotification(byte[] value) {
        String json = new String(value, StandardCharsets.UTF_8);
        String trigger = "GPIO27";
        try {
            JSONObject object = new JSONObject(json);
            trigger = firstPresent(object, trigger, "button", "gpio", "trigger", "name");
            if (object.has("body")) {
                trigger = firstPresent(object.getJSONObject("body"), trigger, "button", "gpio", "trigger", "name");
            }
        } catch (Exception ignored) {
        }

        String finalTrigger = trigger;
        executor.execute(() -> callMatchingEndpoint(finalTrigger));
    }

    private void callMatchingEndpoint(String trigger) {
        List<EndpointConfig> endpoints = new EndpointRepository(this).load();
        for (EndpointConfig endpoint : endpoints) {
            if (trigger.equalsIgnoreCase(endpoint.trigger.trim())) {
                EndpointCaller.Result result = EndpointCaller.call(endpoint);
                Log.i(TAG, "Trigger " + trigger + " called " + endpoint.name
                        + ": " + result.statusCode + " " + result.message);
                return;
            }
        }
        Log.i(TAG, "No endpoint configured for trigger: " + trigger);
    }

    private static String firstPresent(JSONObject object, String fallback, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void stopListening() {
        running = false;
        reconnectScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        closeGatt();
    }

    private void scheduleReconnect(long delayMs) {
        if (!running || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        mainHandler.postDelayed(() -> {
            reconnectScheduled = false;
            connect();
        }, delayMs);
    }

    private void closeGatt() {
        if (gatt == null || !hasConnectPermission()) {
            gatt = null;
            return;
        }
        gatt.close();
        gatt = null;
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ESP32 Button Link",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_tap)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .build();
    }
}
