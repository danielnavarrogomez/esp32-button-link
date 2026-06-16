package com.navarrofernandez.esp32buttonlink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.navarrofernandez.esp32buttonlink.ble.BleSettingsRepository;
import com.navarrofernandez.esp32buttonlink.ble.BleServiceStarter;
import com.navarrofernandez.esp32buttonlink.ble.BleTriggerService;
import com.navarrofernandez.esp32buttonlink.data.EndpointConfig;
import com.navarrofernandez.esp32buttonlink.data.EndpointRepository;
import com.navarrofernandez.esp32buttonlink.net.EndpointCaller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQUEST_BLE_PERMISSIONS = 7001;
    private static final int REQUEST_COMPANION_DEVICE = 7002;
    private static final UUID SERVICE_UUID = UUID.fromString("8f3a5c2e-1b7d-4a5e-9c72-4f41c6d7a001");
    private static final String[] ICON_IDS = {"bolt", "garage", "door", "lock", "light", "bell", "web", "home"};
    private static final String[] COLOR_VALUES = {
            "#0B6EFD", "#14B8A6", "#22C55E", "#F59E0B",
            "#EF4444", "#A855F7", "#EC4899", "#64748B"
    };
    private static final int[] ICON_LABEL_RES = {
            R.string.icon_bolt,
            R.string.icon_garage,
            R.string.icon_door,
            R.string.icon_lock,
            R.string.icon_light,
            R.string.icon_bell,
            R.string.icon_web,
            R.string.icon_home
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EndpointRepository repository;
    private BleSettingsRepository bleSettings;
    private List<EndpointConfig> endpoints;
    private LinearLayout list;
    private TextView listenerStatus;
    private TextView selectedDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        repository = new EndpointRepository(this);
        bleSettings = new BleSettingsRepository(this);
        endpoints = new ArrayList<>(repository.load());
        render();
        BleServiceStarter.startIfReady(this);
    }

    private void render() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 247, 249));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(34), dp(20), dp(40));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(19, 24, 33));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subtitle);
        subtitle.setTextSize(15);
        subtitle.setLineSpacing(dp(2), 1.0f);
        subtitle.setTextColor(Color.rgb(91, 99, 113));
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        selectedDevice = new TextView(this);
        listenerStatus = new TextView(this);
        root.addView(devicePanel());
        updateSelectedDeviceText();

        LinearLayout bleActions = new LinearLayout(this);
        bleActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.setMargins(0, dp(12), 0, dp(16));

        Button selectDevice = new Button(this);
        styleAction(selectDevice, R.string.select_ble_device, Color.rgb(11, 110, 253), true);
        selectDevice.setOnClickListener(v -> startCompanionPairing());
        bleActions.addView(selectDevice, weight());

        Button stopListener = new Button(this);
        styleAction(stopListener, R.string.stop_listener, Color.rgb(100, 116, 139), false);
        stopListener.setOnClickListener(v -> stopBleListener());
        bleActions.addView(stopListener, weight());
        root.addView(bleActions, actionParams);

        Button add = new Button(this);
        add.setAllCaps(false);
        add.setText(R.string.add_tap);
        add.setTextSize(16);
        add.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add.setTextColor(Color.WHITE);
        add.setBackground(round(Color.rgb(11, 110, 253), dp(12)));
        add.setMinHeight(dp(52));
        add.setEnabled(endpoints.size() < EndpointRepository.MAX_ENDPOINTS);
        add.setOnClickListener(v -> showEditor(null));
        root.addView(add, matchWrap());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 16, 0, 0);
        root.addView(list);

        Button advanced = new Button(this);
        advanced.setAllCaps(false);
        advanced.setText(R.string.advanced_setup);
        advanced.setTextSize(14);
        advanced.setTextColor(Color.rgb(78, 86, 101));
        advanced.setBackground(stroke(Color.TRANSPARENT, Color.rgb(180, 187, 198), dp(10)));
        advanced.setOnClickListener(v -> showAdvancedSetup());
        LinearLayout.LayoutParams advancedParams = matchWrap();
        advancedParams.setMargins(0, dp(4), 0, 0);
        root.addView(advanced, advancedParams);

        setContentView(scrollView);
        renderList();
    }

    private LinearLayout devicePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(round(Color.WHITE, dp(8)));
        panel.setElevation(dp(2));

        TextView label = new TextView(this);
        label.setText(R.string.device_label);
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setTextColor(Color.rgb(100, 116, 139));
        panel.addView(label);

        selectedDevice.setTextSize(16);
        selectedDevice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        selectedDevice.setTextColor(Color.rgb(19, 24, 33));
        selectedDevice.setPadding(0, dp(4), 0, dp(8));
        panel.addView(selectedDevice);

        listenerStatus.setTextSize(13);
        listenerStatus.setTextColor(Color.rgb(71, 85, 105));
        panel.addView(listenerStatus);

        return panel;
    }

    private void renderList() {
        list.removeAllViews();
        if (endpoints.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_taps);
            empty.setTextSize(16);
            empty.setTextColor(Color.rgb(91, 99, 113));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            list.addView(empty, matchWrap());
            return;
        }

        for (EndpointConfig endpoint : endpoints) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(round(Color.WHITE, dp(8)));
            card.setElevation(dp(2));

            int endpointColor = parseColor(endpoint.color, Color.rgb(11, 110, 253));
            TextView badge = new TextView(this);
            badge.setText(iconShortLabel(endpoint.iconId));
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(Color.WHITE);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setTextSize(15);
            badge.setBackground(round(endpointColor, dp(8)));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(56), dp(56));
            badgeParams.setMargins(0, 0, dp(14), 0);
            card.addView(badge, badgeParams);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            name.setText(endpoint.name);
            name.setTextSize(20);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setTextColor(Color.rgb(19, 24, 33));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(name);

            TextView details = new TextView(this);
            details.setText(endpoint.trigger + "  ->  " + endpoint.method + "  " + endpoint.url);
            details.setTextColor(Color.rgb(91, 99, 113));
            details.setTextSize(13);
            details.setSingleLine(true);
            details.setEllipsize(TextUtils.TruncateAt.END);
            details.setPadding(0, dp(2), 0, dp(12));
            content.addView(details);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button test = new Button(this);
            styleAction(test, R.string.test, endpointColor, true);
            test.setOnClickListener(v -> testEndpoint(endpoint));
            actions.addView(test, weight());

            Button edit = new Button(this);
            styleAction(edit, R.string.edit, Color.rgb(78, 86, 101), false);
            edit.setOnClickListener(v -> showEditor(endpoint));
            actions.addView(edit, weight());

            Button delete = new Button(this);
            styleAction(delete, R.string.delete, Color.rgb(194, 65, 65), false);
            delete.setOnClickListener(v -> confirmDelete(endpoint));
            actions.addView(delete, weight());

            content.addView(actions);
            card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams params = matchWrap();
            params.setMargins(0, 0, 0, dp(14));
            list.addView(card, params);
        }
    }

    private void showEditor(EndpointConfig existing) {
        EndpointConfig draft = existing == null ? new EndpointConfig() : copy(existing);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(32, 16, 32, 0);

        EditText name = field(getString(R.string.name), draft.name, InputType.TYPE_CLASS_TEXT);
        EditText trigger = field(getString(R.string.button_gpio_name), draft.trigger, InputType.TYPE_CLASS_TEXT);
        EditText url = field(getString(R.string.url), draft.url, InputType.TYPE_TEXT_VARIATION_URI);
        Spinner method = new Spinner(this);
        method.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"GET", "POST"}));
        method.setSelection("POST".equalsIgnoreCase(draft.method) ? 1 : 0);
        Spinner icon = new Spinner(this);
        icon.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, iconLabels()));
        icon.setSelection(iconIndex(draft.iconId));
        EditText username = field(getString(R.string.username), draft.username, InputType.TYPE_CLASS_TEXT);
        EditText password = field(getString(R.string.password), draft.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox credentialsAsParams = new CheckBox(this);
        credentialsAsParams.setText(R.string.credentials_as_params);
        credentialsAsParams.setChecked(draft.credentialsAsParams);
        EditText params = field(getString(R.string.params_hint), draft.params, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        params.setMinLines(3);
        String[] selectedColor = {normalizeColor(draft.color)};
        LinearLayout colorPalette = colorPalette(selectedColor);

        form.addView(name);
        form.addView(trigger);
        form.addView(url);
        form.addView(label(getString(R.string.method)));
        form.addView(method);
        form.addView(label(getString(R.string.android_auto_icon)));
        form.addView(icon);
        form.addView(username);
        form.addView(password);
        form.addView(credentialsAsParams);
        form.addView(params);
        form.addView(label(getString(R.string.button_color)));
        form.addView(colorPalette);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_tap : R.string.edit_tap)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            draft.name = clean(name.getText().toString(), "Untitled");
            draft.trigger = clean(trigger.getText().toString(), "GPIO27");
            draft.url = clean(url.getText().toString(), "");
            draft.method = method.getSelectedItem().toString();
            draft.iconId = ICON_IDS[icon.getSelectedItemPosition()];
            draft.username = username.getText().toString();
            draft.password = password.getText().toString();
            draft.credentialsAsParams = credentialsAsParams.isChecked();
            draft.params = params.getText().toString();
            draft.color = selectedColor[0];

            if (draft.url.isEmpty() || !(draft.url.startsWith("http://") || draft.url.startsWith("https://"))) {
                Toast.makeText(this, R.string.url_error, Toast.LENGTH_LONG).show();
                return;
            }

            if (existing == null) {
                endpoints.add(draft);
            } else {
                int index = endpoints.indexOf(existing);
                endpoints.set(index, draft);
            }
            repository.save(endpoints);
            renderList();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private EditText field(String hint, String value, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setInputType(inputType);
        editText.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        return editText;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(91, 99, 113));
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(16), 0, dp(6));
        return label;
    }

    private void testEndpoint(EndpointConfig endpoint) {
        Toast.makeText(this, getString(R.string.calling, endpoint.name), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            EndpointCaller.Result result = EndpointCaller.call(endpoint);
            mainHandler.post(() -> Toast.makeText(this, formatResult(result), Toast.LENGTH_LONG).show());
        });
    }

    private void confirmDelete(EndpointConfig endpoint) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_tap)
                .setMessage(endpoint.name)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    endpoints.remove(endpoint);
                    repository.save(endpoints);
                    renderList();
                })
                .show();
    }

    private static EndpointConfig copy(EndpointConfig source) {
        EndpointConfig copy = new EndpointConfig();
        copy.id = source.id;
        copy.name = source.name;
        copy.url = source.url;
        copy.method = source.method;
        copy.username = source.username;
        copy.password = source.password;
        copy.credentialsAsParams = source.credentialsAsParams;
        copy.params = source.params;
        copy.color = source.color;
        copy.iconId = source.iconId;
        copy.trigger = source.trigger;
        return copy;
    }

    private void showBleDevicePicker() {
        if (!ensureBlePermissions()) {
            return;
        }

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (adapter == null || !adapter.isEnabled() || scanner == null) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>();
        ArrayAdapter<String> adapterList = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        addBondedDevices(adapter, devices, adapterList);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.select_ble_device)
                .setAdapter(adapterList, (d, which) -> {
                    BluetoothDevice device = devices.get(which);
                    saveSelectedDevice(device.getAddress(), deviceName(device));
                    stopBleScan(scanner, null);
                })
                .setMessage(R.string.scan_empty_hint)
                .setNeutralButton(R.string.manual_address, null)
                .setNegativeButton(R.string.cancel, (d, which) -> stopBleScan(scanner, null))
                .create();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                addDevice(result.getDevice(), devices, adapterList);
            }

            @Override
            public void onScanFailed(int errorCode) {
                mainHandler.post(() -> Toast.makeText(
                        MainActivity.this,
                        getString(R.string.scan_failed, errorCode),
                        Toast.LENGTH_LONG).show());
            }
        };

        dialog.setOnDismissListener(d -> stopBleScan(scanner, callback));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> showManualBleAddressDialog());
        Toast.makeText(this, R.string.scan_started, Toast.LENGTH_SHORT).show();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, callback);
        } catch (SecurityException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        mainHandler.postDelayed(() -> stopBleScan(scanner, callback), 10000);
    }

    private void showAdvancedSetup() {
        String[] options = {
                getString(R.string.fallback_scan),
                getString(R.string.battery_settings)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.advanced_setup)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showBleDevicePicker();
                    } else {
                        openBatterySettings();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startCompanionPairing() {
        if (!ensureBlePermissions()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            Toast.makeText(this, R.string.companion_pairing_unavailable, Toast.LENGTH_LONG).show();
            showBleDevicePicker();
            return;
        }

        CompanionDeviceManager manager =
                (CompanionDeviceManager) getSystemService(COMPANION_DEVICE_SERVICE);
        if (manager == null) {
            Toast.makeText(this, R.string.companion_pairing_unavailable, Toast.LENGTH_LONG).show();
            showBleDevicePicker();
            return;
        }

        BluetoothLeDeviceFilter filter = new BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("ESP32 Button Link"))
                .setScanFilter(new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .build())
                .build();
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(false)
                .build();

        CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
            @Override
            public void onDeviceFound(IntentSender chooserLauncher) {
                launchCompanionChooser(chooserLauncher);
            }

            @Override
            public void onAssociationPending(IntentSender intentSender) {
                launchCompanionChooser(intentSender);
            }

            @Override
            public void onAssociationCreated(AssociationInfo associationInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && associationInfo.getDeviceMacAddress() != null) {
                    saveSelectedDevice(
                            associationInfo.getDeviceMacAddress().toString(),
                            associationInfo.getDisplayName() == null
                                    ? "ESP32 Button Link"
                                    : associationInfo.getDisplayName().toString());
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                Toast.makeText(
                        MainActivity.this,
                        getString(R.string.companion_pairing_failed, error == null ? "unknown" : error),
                        Toast.LENGTH_LONG).show();
                showBleDevicePicker();
            }
        };

        Toast.makeText(this, R.string.companion_pairing_started, Toast.LENGTH_SHORT).show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(request, Runnable::run, callback);
        } else {
            manager.associate(request, callback, null);
        }
    }

    private void launchCompanionChooser(IntentSender chooserLauncher) {
        try {
            startIntentSenderForResult(
                    chooserLauncher,
                    REQUEST_COMPANION_DEVICE,
                    null,
                    0,
                    0,
                    0);
        } catch (IntentSender.SendIntentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addBondedDevices(
            BluetoothAdapter adapter,
            List<BluetoothDevice> devices,
            ArrayAdapter<String> adapterList) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return;
        }
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null) {
                return;
            }
            for (BluetoothDevice device : bondedDevices) {
                addDevice(device, devices, adapterList);
            }
        } catch (SecurityException ignored) {
        }
    }

    private void addDevice(
            BluetoothDevice device,
            List<BluetoothDevice> devices,
            ArrayAdapter<String> adapterList) {
        String address = device.getAddress();
        for (BluetoothDevice existing : devices) {
            if (existing.getAddress().equals(address)) {
                return;
            }
        }
        devices.add(device);
        mainHandler.post(() -> {
            adapterList.add(deviceName(device) + "\n" + address);
            adapterList.notifyDataSetChanged();
        });
    }

    private void showManualBleAddressDialog() {
        EditText address = field(
                getString(R.string.manual_ble_address),
                bleSettings.deviceAddress(),
                InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(this)
                .setTitle(R.string.manual_address)
                .setView(address)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String value = address.getText().toString().trim();
                    if (!BluetoothAdapter.checkBluetoothAddress(value)) {
                        Toast.makeText(this, R.string.invalid_ble_address, Toast.LENGTH_LONG).show();
                        return;
                    }
                    saveSelectedDevice(value, "ESP32 Button Link");
                })
                .show();
    }

    private void saveSelectedDevice(String address, String name) {
        bleSettings.saveDevice(address, name);
        updateSelectedDeviceText();
        BleServiceStarter.observePresenceIfPossible(this);
        startBleListener();
        requestBatteryOptimizationExemption();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_COMPANION_DEVICE || resultCode != RESULT_OK || data == null) {
            return;
        }

        Object selected = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
        if (selected instanceof ScanResult) {
            BluetoothDevice device = ((ScanResult) selected).getDevice();
            saveSelectedDevice(device.getAddress(), deviceName(device));
        } else if (selected instanceof BluetoothDevice) {
            BluetoothDevice device = (BluetoothDevice) selected;
            saveSelectedDevice(device.getAddress(), deviceName(device));
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        Toast.makeText(this, R.string.reliability_settings, Toast.LENGTH_LONG).show();
        openBatterySettings();
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            Intent settings = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(settings);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            Toast.makeText(this, R.string.select_ble_device, Toast.LENGTH_SHORT).show();
            BleServiceStarter.startIfReady(this);
        }
    }

    private void startBleListener() {
        if (bleSettings.deviceAddress().isEmpty()) {
            Toast.makeText(this, R.string.select_device_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureBlePermissions()) {
            return;
        }

        if (BleServiceStarter.startIfReady(this)) {
            Toast.makeText(this, R.string.listener_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopBleListener() {
        Intent intent = new Intent(this, BleTriggerService.class);
        intent.setAction(BleTriggerService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, R.string.listener_stopped, Toast.LENGTH_SHORT).show();
    }

    private void updateSelectedDeviceText() {
        if (selectedDevice == null) {
            return;
        }
        String name = bleSettings.deviceName();
        String address = bleSettings.deviceAddress();
        if (address.isEmpty()) {
            selectedDevice.setText(R.string.no_ble_device_selected);
            if (listenerStatus != null) {
                listenerStatus.setText(R.string.connection_missing);
                listenerStatus.setTextColor(Color.rgb(148, 62, 62));
            }
        } else {
            selectedDevice.setText(getString(R.string.selected_ble_device, name, address));
            if (listenerStatus != null) {
                listenerStatus.setText(R.string.connection_ready);
                listenerStatus.setTextColor(Color.rgb(22, 101, 52));
            }
        }
    }

    private void stopBleScan(BluetoothLeScanner scanner, ScanCallback callback) {
        if (callback == null || !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return;
        }
        try {
            scanner.stopScan(callback);
        } catch (Exception ignored) {
        }
    }

    private boolean ensureBlePermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredBlePermissions()) {
            if (!hasPermission(permission)) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
            return false;
        }
        return true;
    }

    private String[] requiredBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Manifest.permission.BLUETOOTH_SCAN.equals(permission)
                || Manifest.permission.BLUETOOTH_CONNECT.equals(permission)) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String deviceName(BluetoothDevice device) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return "BLE device";
        }
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? "BLE device" : name;
    }

    private static int iconIndex(String iconId) {
        for (int i = 0; i < ICON_IDS.length; i++) {
            if (ICON_IDS[i].equals(iconId)) {
                return i;
            }
        }
        return 0;
    }

    private static String formatResult(EndpointCaller.Result result) {
        if (result.ok) {
            return "OK: HTTP 200";
        }
        if (result.statusCode > 0) {
            return "Error: HTTP " + result.statusCode + " " + result.message;
        }
        return "Error: " + result.message;
    }

    private static String clean(String value, String fallback) {
        String cleaned = value.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static int parseColor(String color, int fallback) {
        try {
            return Color.parseColor(color);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout colorPalette(String[] selectedColor) {
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.VERTICAL);
        List<TextView> swatches = new ArrayList<>();

        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 4; col++) {
                String value = COLOR_VALUES[row * 4 + col];
                TextView swatch = new TextView(this);
                swatch.setText(value.equals(selectedColor[0]) ? "✓" : "");
                swatch.setGravity(Gravity.CENTER);
                swatch.setTextColor(Color.WHITE);
                swatch.setTextSize(20);
                swatch.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                swatch.setBackground(swatchBackground(value, value.equals(selectedColor[0])));
                swatch.setOnClickListener(v -> {
                    selectedColor[0] = value;
                    for (TextView item : swatches) {
                        String color = (String) item.getTag();
                        boolean selected = color.equals(selectedColor[0]);
                        item.setText(selected ? "✓" : "");
                        item.setBackground(swatchBackground(color, selected));
                    }
                });
                swatch.setTag(value);
                swatches.add(swatch);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                line.addView(swatch, params);
            }
            palette.addView(line, matchWrap());
        }
        return palette;
    }

    private GradientDrawable swatchBackground(String color, boolean selected) {
        GradientDrawable drawable = round(parseColor(color, Color.rgb(11, 110, 253)), dp(12));
        drawable.setStroke(selected ? dp(3) : dp(1), selected ? Color.rgb(19, 24, 33) : Color.rgb(220, 224, 231));
        return drawable;
    }

    private static String normalizeColor(String rawColor) {
        for (String color : COLOR_VALUES) {
            if (color.equalsIgnoreCase(rawColor)) {
                return color;
            }
        }
        return "#0B6EFD";
    }

    private void styleAction(Button button, int label, int color, boolean filled) {
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(40));
        button.setTextColor(filled ? Color.WHITE : color);
        button.setBackground(filled ? round(color, dp(8)) : stroke(Color.TRANSPARENT, color, dp(8)));
    }

    private String[] iconLabels() {
        String[] labels = new String[ICON_LABEL_RES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(ICON_LABEL_RES[i]);
        }
        return labels;
    }

    private static String iconShortLabel(String iconId) {
        if ("garage".equals(iconId)) return "G";
        if ("door".equals(iconId)) return "D";
        if ("lock".equals(iconId)) return "L";
        if ("light".equals(iconId)) return "I";
        if ("bell".equals(iconId)) return "B";
        if ("web".equals(iconId)) return "W";
        if ("home".equals(iconId)) return "H";
        return "B";
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static GradientDrawable stroke(int color, int strokeColor, int radius) {
        GradientDrawable drawable = round(color, radius);
        drawable.setStroke(2, strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
