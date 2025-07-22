package com.tsinghua.healthmirror;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;


import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HealthMirror";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 2;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread readThread;
    private boolean isConnected = false;

    private ListView deviceListView;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayList<String> deviceList;
    private ArrayList<BluetoothDevice> bluetoothDeviceList;

    private TextView statusText, deviceInfoText;
    private EditText patientInfoEdit;
    private Button scanButton, connectButton, syncTimeButton;
    private Button startCaptureButton, stopCaptureButton, refreshInfoButton;
    private Button configWifiButton;

    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBluetooth();
        setupListeners();

        mainHandler = new Handler(Looper.getMainLooper());

        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
    }

    private void initViews() {
        deviceListView = findViewById(R.id.deviceListView);
        statusText = findViewById(R.id.statusText);
        deviceInfoText = findViewById(R.id.deviceInfoText);
        patientInfoEdit = findViewById(R.id.patientInfoEdit);

        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        syncTimeButton = findViewById(R.id.syncTimeButton);
        startCaptureButton = findViewById(R.id.startCaptureButton);
        stopCaptureButton = findViewById(R.id.stopCaptureButton);
        refreshInfoButton = findViewById(R.id.refreshInfoButton);
        configWifiButton = findViewById(R.id.configWifiButton);

        deviceList = new ArrayList<>();
        bluetoothDeviceList = new ArrayList<>();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        deviceListView.setAdapter(deviceAdapter);
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermissions();
                return;
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            loadPairedDevices();
        }
    }

    private void requestBluetoothPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSION);
    }

    private void setupListeners() {
        scanButton.setOnClickListener(v -> startDiscovery());
        connectButton.setOnClickListener(v -> connectToDevice());
        syncTimeButton.setOnClickListener(v -> syncTime());
        startCaptureButton.setOnClickListener(v -> startCapture());
        stopCaptureButton.setOnClickListener(v -> stopCapture());
        refreshInfoButton.setOnClickListener(v -> refreshInfo());
        configWifiButton.setOnClickListener(v -> configWifi());

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < bluetoothDeviceList.size()) {
                connectedDevice = bluetoothDeviceList.get(position);
                statusText.setText("Selected: " + connectedDevice.getName());
            }
        });
    }

    private void loadPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        deviceList.clear();
        bluetoothDeviceList.clear();

        for (BluetoothDevice device : pairedDevices) {
            String deviceName = device.getName();
            if (deviceName != null && deviceName.contains("HealthMirror")) {
                deviceList.add(deviceName + "\n" + device.getAddress());
                bluetoothDeviceList.add(device);
            }
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private void startDiscovery() {
        // 检查并请求所有必要权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLUETOOTH_PERMISSION);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLUETOOTH_PERMISSION);
                return;
            }
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        deviceList.clear();
        bluetoothDeviceList.clear();
        loadPairedDevices(); // 先加载已配对设备

        bluetoothAdapter.startDiscovery();
        statusText.setText("正在扫描设备...");
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                String deviceName = device.getName();
                if (deviceName != null && deviceName.contains("HealthMirror")) {
                    if (!bluetoothDeviceList.contains(device)) {
                        deviceList.add(deviceName + "\n" + device.getAddress());
                        bluetoothDeviceList.add(device);
                        deviceAdapter.notifyDataSetChanged();
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                statusText.setText("Scan completed. Found " + deviceList.size() + " devices.");
            }
        }
    };

    private void connectToDevice() {
        if (connectedDevice == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                bluetoothSocket = connectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();

                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                outputStream = bluetoothSocket.getOutputStream();

                isConnected = true;
                startReadThread();

                mainHandler.post(() -> {
                    statusText.setText("Connected to " + connectedDevice.getName());
                    Toast.makeText(MainActivity.this, "Connected successfully!", Toast.LENGTH_SHORT).show();
                });

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                mainHandler.post(() -> {
                    statusText.setText("Connection failed: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Connection failed!", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (isConnected && bluetoothSocket != null) {
                try {
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        String receivedData = new String(buffer, 0, bytes);
                        Log.d(TAG, "Received: " + receivedData);

                        mainHandler.post(() -> handleReceivedData(receivedData));
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading from socket", e);
                    break;
                }
            }
        });
        readThread.start();
    }

    private void handleReceivedData(String data) {
        try {
            JSONObject json = new JSONObject(data);

            if (json.has("ack")) {
                JSONObject ack = json.getJSONObject("ack");
                String command = ack.getString("command");
                String status = ack.getString("status");
                statusText.setText("ACK: " + command + " - " + status);

            } else if (json.has("info")) {
                JSONObject info = json.getJSONObject("info");
                StringBuilder infoText = new StringBuilder();
                infoText.append("Device ID: ").append(info.getInt("device_id")).append("\n");
                infoText.append("Patient Count: ").append(info.getInt("patient_count")).append("\n");
                infoText.append("Space Remaining: ").append(info.getInt("space_remaining")).append(" MB\n");
                infoText.append("Battery Level: ").append(info.getInt("battery_level")).append("%");

                deviceInfoText.setText(infoText.toString());

                // Send ACK for info command
                sendAck("info", "success");
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON", e);
            statusText.setText("Received: " + data);
        }
    }

    private void sendCommand(JSONObject command) {
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, "Not connected to device", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                String commandStr = command.toString();
                outputStream.write(commandStr.getBytes());
                outputStream.flush();

                Log.d(TAG, "Sent: " + commandStr);
                mainHandler.post(() -> statusText.setText("Sent: " + commandStr));

            } catch (IOException e) {
                Log.e(TAG, "Error sending command", e);
                mainHandler.post(() -> statusText.setText("Send failed: " + e.getMessage()));
            }
        }).start();
    }

    private void sendAck(String command, String status) {
        try {
            JSONObject ack = new JSONObject();
            JSONObject ackData = new JSONObject();
            ackData.put("command", command);
            ackData.put("status", status);
            ack.put("ack", ackData);

            sendCommand(ack);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating ACK", e);
        }
    }

    private void syncTime() {
        try {
            JSONObject command = new JSONObject();
            JSONObject timeData = new JSONObject();
            timeData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("set_time", timeData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating sync time command", e);
        }
    }

    private void startCapture() {
        String patientInfo = patientInfoEdit.getText().toString().trim();
        if (patientInfo.isEmpty()) {
            patientInfo = "未知患者信息";
        }

        try {
            JSONObject command = new JSONObject();
            JSONObject captureData = new JSONObject();
            captureData.put("patient_info", patientInfo);
            captureData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("start_capture", captureData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating start capture command", e);
        }
    }

    private void stopCapture() {
        try {
            JSONObject command = new JSONObject();
            JSONObject stopData = new JSONObject();
            stopData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("stop_capture", stopData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating stop capture command", e);
        }
    }

    private void refreshInfo() {
        try {
            JSONObject command = new JSONObject();
            JSONObject refreshData = new JSONObject();
            refreshData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("refresh_info", refreshData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating refresh info command", e);
        }
    }

    private void configWifi() {
        // This would typically open a dialog for WiFi configuration
        // For demonstration, using hardcoded values
        try {
            JSONObject command = new JSONObject();
            JSONObject wifiData = new JSONObject();
            wifiData.put("ssid", "Tsinghua_Secure");
            wifiData.put("auth", "EAP_PEAP");
            wifiData.put("username", "zhangsan24");
            wifiData.put("password", "1234abcd");
            wifiData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("config_wifi", wifiData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating config wifi command", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        isConnected = false;

        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
        }

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }

        unregisterReceiver(discoveryReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                initBluetooth();
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                loadPairedDevices();
            } else {
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_SHORT).show();
            }
        }
    }
}