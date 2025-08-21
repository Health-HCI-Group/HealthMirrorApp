package com.tsinghua.healthmirror;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HealthMirror";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 2;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_BLUETOOTH_CONNECT = 100;
    private static final int REQUEST_IMAGE_CAPTURE = 101;
    private static final int REQUEST_CAMERA_PERMISSION = 102;
    private static final int REQUEST_STORAGE_PERMISSION = 103;

    // 服务器配置
    private static final String SERVER_IP = "47.93.46.224";
    private static final String SSH_USERNAME = "healthmirror";
    private static final String SSH_PASSWORD = "healthmirror1234!";
    private static final String REMOTE_PATH = "/uploads/";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread readThread;
    private boolean isConnected = false;
    private boolean isCapturing = false;
    private int remainingCaptureTime = 0;
    private Handler captureHandler = new Handler();
    private Runnable captureTimer;

    private ListView deviceListView;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayList<String> deviceList;
    private ArrayList<BluetoothDevice> bluetoothDeviceList;

    private TextView statusText, deviceInfoText;
    private EditText patientIdEdit, patientCustomInfoEdit;
    private Button scanButton, connectButton, syncTimeButton;
    private Button startCaptureButton, stopCaptureButton, refreshInfoButton;
    private Button ttsButton;
    private Button configWifiButton, capturePhotoButton, settingsButton;
    private ProgressBar captureProgressBar;
    private TextView captureTimeText;
    private LinearLayout healthIndicatorsContainer;

    private Handler mainHandler;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String currentPhotoPath;
    private SharedPreferences prefs;
    private Map<String, EditText> healthInputs; // 存储动态生成的健康指标输入框

    private TTSManager ttsManager; // 添加TTS管理器

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBluetooth();
        setupListeners();
        ttsManager = TTSManager.getInstance(this);

        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("HealthMirrorSettings", MODE_PRIVATE);
        healthInputs = new HashMap<>();
        createHealthIndicators();

        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次返回主界面时重新创建健康指标输入框，以反映设置的变化
        createHealthIndicators();
    }

    private void initViews() {
        deviceListView = findViewById(R.id.deviceListView);
        statusText = findViewById(R.id.statusText);
        deviceInfoText = findViewById(R.id.deviceInfoText);
        patientIdEdit = findViewById(R.id.patientIdEdit);
        patientCustomInfoEdit = findViewById(R.id.patientCustomInfoEdit);
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        syncTimeButton = findViewById(R.id.syncTimeButton);
        startCaptureButton = findViewById(R.id.startCaptureButton);
        stopCaptureButton = findViewById(R.id.stopCaptureButton);
        ttsButton = findViewById(R.id.toggleTtsButton);
        refreshInfoButton = findViewById(R.id.refreshInfoButton);
        configWifiButton = findViewById(R.id.configWifiButton);
        capturePhotoButton = findViewById(R.id.capturePhotoButton);
        settingsButton = findViewById(R.id.settingsButton);
        healthIndicatorsContainer = findViewById(R.id.healthIndicatorsContainer);

        deviceList = new ArrayList<>();
        bluetoothDeviceList = new ArrayList<>();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        deviceListView.setAdapter(deviceAdapter);
        captureProgressBar = findViewById(R.id.captureProgressBar);
        captureTimeText = findViewById(R.id.captureTimeText);
    }

    private void createHealthIndicators() {
        if (healthIndicatorsContainer == null) return;

        // 清除现有的输入框
        healthIndicatorsContainer.removeAllViews();
        healthInputs.clear();

        // 获取所有健康指标标签
        List<SettingsActivity.HealthTag> allTags = SettingsActivity.getAllTags(prefs);
        for (SettingsActivity.HealthTag tag : allTags) {
            // 为血压创建特殊的双输入框布局
            if ("bloodPressureSystolic".equals(tag.key)) {
                createBloodPressureInputs(allTags);
                continue;
            } else if ("bloodPressureDiastolic".equals(tag.key)) {
                // 舒张压已在收缩压中处理，跳过
                continue;
            }

            // 创建普通输入框
            EditText editText = new EditText(this);
            editText.setHint(tag.name + (TextUtils.isEmpty(tag.unit) ? "" : "(" + tag.unit + ")"));
            editText.setPadding(12, 12, 12, 12);
            editText.setBackground(getResources().getDrawable(android.R.drawable.editbox_background));

            // 设置输入类型
            if (tag.key.contains("Rate") || tag.key.contains("Oxygen") || tag.key.contains("pressure")) {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            } else if (tag.key.contains("Temp")) {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            } else {
                editText.setInputType(InputType.TYPE_CLASS_TEXT);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = 8;
            editText.setLayoutParams(params);

            healthIndicatorsContainer.addView(editText);
            healthInputs.put(tag.key, editText);
        }
    }

    private void createBloodPressureInputs(List<SettingsActivity.HealthTag> allTags) {
        // 创建血压输入框的容器
        LinearLayout bpContainer = new LinearLayout(this);
        bpContainer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        containerParams.topMargin = 8;
        bpContainer.setLayoutParams(containerParams);

        // 收缩压输入框
        EditText systolicEdit = new EditText(this);
        systolicEdit.setHint("收缩压(mmHg)");
        systolicEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        systolicEdit.setPadding(12, 12, 12, 12);
        systolicEdit.setBackground(getResources().getDrawable(android.R.drawable.editbox_background));
        LinearLayout.LayoutParams systolicParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        );
        systolicEdit.setLayoutParams(systolicParams);

        // 分隔符
        TextView separator = new TextView(this);
        separator.setText("/");
        separator.setTextSize(18);
        separator.setPadding(8, 8, 8, 8);

        // 舒张压输入框
        EditText diastolicEdit = new EditText(this);
        diastolicEdit.setHint("舒张压(mmHg)");
        diastolicEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        diastolicEdit.setPadding(12, 12, 12, 12);
        diastolicEdit.setBackground(getResources().getDrawable(android.R.drawable.editbox_background));
        LinearLayout.LayoutParams diastolicParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        );
        diastolicEdit.setLayoutParams(diastolicParams);

        bpContainer.addView(systolicEdit);
        bpContainer.addView(separator);
        bpContainer.addView(diastolicEdit);

        healthIndicatorsContainer.addView(bpContainer);

        // 存储到输入框映射中
        healthInputs.put("bloodPressureSystolic", systolicEdit);
        healthInputs.put("bloodPressureDiastolic", diastolicEdit);
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
                return;
            }
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
        startCaptureButton.setOnClickListener(v -> startCaptureWithDefaultDuration());
        stopCaptureButton.setOnClickListener(v -> stopCapture());
        refreshInfoButton.setOnClickListener(v -> refreshInfo());
        configWifiButton.setOnClickListener(v -> configWifi());
        capturePhotoButton.setOnClickListener(v -> dispatchTakePictureIntent());
        settingsButton.setOnClickListener(v -> openSettings());
        ttsButton.setOnClickListener(v-> toggleTTS());
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < bluetoothDeviceList.size()) {
                connectedDevice = bluetoothDeviceList.get(position);
                statusText.setText("Selected: " + connectedDevice.getName());
            }
        });
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void startCaptureWithDefaultDuration() {
        int defaultDuration = SettingsActivity.getDefaultCaptureDuration(prefs);
        startCaptureWithDuration(defaultDuration);
    }

    private void startCaptureWithDuration(int durationSeconds) {
        // 获取基础患者信息
        String patientId = patientIdEdit.getText().toString().trim();

        // 构建结构化健康数据
        JSONObject healthData = new JSONObject();
        try {
            // 基础信息
            if(!patientId.isEmpty()) healthData.put("patient_id", patientId);

            // 生命体征
            JSONObject vitals = new JSONObject();

            // 遍历所有健康指标输入框
            for (Map.Entry<String, EditText> entry : healthInputs.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue().getText().toString().trim();

                if (!value.isEmpty()) {
                    switch (key) {
                        case "bloodPressureSystolic":
                            String diastolic = "";
                            if (healthInputs.containsKey("bloodPressureDiastolic")) {
                                diastolic = healthInputs.get("bloodPressureDiastolic").getText().toString().trim();
                            }
                            if (!diastolic.isEmpty()) {
                                vitals.put("blood_pressure", value + "/" + diastolic);
                            }
                            break;
                        case "bloodPressureDiastolic":
                            // 已在收缩压中处理
                            break;
                        case "heartRate":
                            vitals.put("heart_rate", value + "bpm");
                            break;
                        case "bodyTemp":
                            vitals.put("temperature", value + "℃");
                            break;
                        case "bloodOxygen":
                            vitals.put("blood_oxygen", value + "%");
                            break;
                        case "medicalNotes":
                            healthData.put("notes", value);
                            break;
                        case "respiratoryRate":
                            vitals.put("respiratory_rate", value + "bpm");
                            break;
                        default:
                            // 自定义指标
                            vitals.put(key, value);
                            break;
                    }
                }
            }

            healthData.put("vitals", vitals);

            // 发送数据
            JSONObject command = new JSONObject();
            command.put("start_capture", new JSONObject()
                    .put("patient_info", healthData.toString())
                    .put("duration", durationSeconds)
                    .put("time", System.currentTimeMillis() / 1000.0));

            sendCommand(command);
            if(isConnected) {
                ttsManager.showToastWithTTS("开始采集");
                startCaptureTimer(durationSeconds);
            }
        } catch (JSONException e) {
            Log.e(TAG, "健康数据构建失败", e);
            Toast.makeText(this, "健康数据格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCaptureTimer(int totalSeconds) {
        isCapturing = true;
        remainingCaptureTime = totalSeconds;
        LinearLayout timeBar = findViewById(R.id.TimeBar);
        timeBar.setVisibility(View.VISIBLE);

        captureProgressBar.setMax(totalSeconds);
        captureProgressBar.setProgress(totalSeconds);
        captureProgressBar.setVisibility(View.VISIBLE);
        captureTimeText.setVisibility(View.VISIBLE);
        captureTimeText.setText(formatTime(remainingCaptureTime));

        startCaptureButton.setEnabled(false);
        stopCaptureButton.setEnabled(true);

        captureTimer = new Runnable() {
            @Override
            public void run() {
                remainingCaptureTime--;
                captureProgressBar.setProgress(remainingCaptureTime);
                captureTimeText.setText(formatTime(remainingCaptureTime));

                if (remainingCaptureTime <= 0) {
                    stopCapture();
                } else {
                    captureHandler.postDelayed(this, 1000);
                }
            }
        };

        captureHandler.postDelayed(captureTimer, 1000);
    }

    protected void stopCapture() {
        try {
            JSONObject command = new JSONObject();
            JSONObject stopData = new JSONObject();
            stopData.put("time", System.currentTimeMillis() / 1000.0);
            command.put("stop_capture", stopData);

            sendCommand(command);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating stop capture command", e);
        }

        if (captureTimer != null) {
            captureHandler.removeCallbacks(captureTimer);
        }

        isCapturing = false;
        captureProgressBar.setVisibility(View.GONE);
        captureTimeText.setVisibility(View.GONE);

        startCaptureButton.setEnabled(true);
        stopCaptureButton.setEnabled(false);
    }

    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, secs);
    }

    private void dispatchTakePictureIntent() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CAMERA_PERMISSION);
            return;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = createImageFile();
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.tsinghua.healthmirror.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    private File createImageFile() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = null;
        try {
            image = File.createTempFile(
                    imageFileName,
                    ".jpg",
                    storageDir
            );
            currentPhotoPath = image.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error creating image file", e);
        }
        return image;
    }

    private void loadPairedDevices() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        deviceList.clear();
        bluetoothDeviceList.clear();
        Log.e("pairedDevices", pairedDevices.toString());
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
        loadPairedDevices();

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
            ttsManager.showToastWithTTS("请先选择设备");
            return;
        }

        new Thread(() -> {
            try {
                bluetoothSocket = connectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();

                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                outputStream = bluetoothSocket.getOutputStream();

                isConnected = true;
                startReadThread();

                mainHandler.post(() -> {
                    statusText.setText("Connected to " + connectedDevice.getName());
                    ttsManager.showToastWithTTS("连接成功");
                });
                Log.d(TAG, "BluetoothSocket isConnected: " + bluetoothSocket.isConnected());

            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                isConnected = false; // 确保连接失败时设置为false
                mainHandler.post(() -> {
                    statusText.setText("Connection failed: " + e.getMessage());
                    ttsManager.showToastWithTTS("连接失败");
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
            isConnected = false;
            ttsManager.showToastWithTTS("蓝牙连接已断开");

        });
        readThread.start();
    }

    private void handleReceivedData(String data) {
        Log.d(TAG, "Handling received data: " + data);
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
        if (!isConnected || outputStream == null || bluetoothSocket == null || !bluetoothSocket.isConnected()) {
            ttsManager.showToastWithTTS("设备未连接");
            return;
        }

        new Thread(() -> {
            try {
                String commandStr = command.toString();
                Log.d(TAG, "准备发送原始数据: " + commandStr);
                byte[] bytes = commandStr.getBytes(StandardCharsets.UTF_8);
                Log.d(TAG, "字节数组长度: " + bytes.length);

                outputStream.write(bytes);
                outputStream.flush();
                Log.d(TAG, "数据已刷新到输出流");

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
        // 从SharedPreferences加载上次的配置
        String lastSsid = prefs.getString("wifi_last_ssid", "");
        String lastAuth = prefs.getString("wifi_last_auth", "WPA2_PSK");
        String lastUsername = prefs.getString("wifi_last_username", "");
        boolean savePassword = prefs.getBoolean("wifi_save_password", false);
        String lastPassword = savePassword ? prefs.getString("wifi_last_password", "") : "";

        // 创建输入框
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        // 网络名输入框
        final EditText ssidInput = new EditText(this);
        ssidInput.setHint("网络名");
        ssidInput.setText(lastSsid); // 恢复上次的SSID
        layout.addView(ssidInput);

        // 认证方式下拉选择
        TextView authLabel = new TextView(this);
        authLabel.setText("认证方式:");
        authLabel.setPadding(0, 20, 0, 10);
        layout.addView(authLabel);

        // 创建Spinner用于认证方式选择
        android.widget.Spinner authSpinner = new android.widget.Spinner(this);
        String[] authOptions = {"开放网络", "WPA2-PSK", "EAP-PEAP", "EAP-TTLS"};
        String[] authValues = {"OPEN", "WPA2_PSK", "EAP_PEAP", "EAP_TTLS"};
        ArrayAdapter<String> authAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, authOptions);
        authAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        authSpinner.setAdapter(authAdapter);

        // 设置上次选择的认证方式
        for (int i = 0; i < authValues.length; i++) {
            if (authValues[i].equals(lastAuth)) {
                authSpinner.setSelection(i);
                break;
            }
        }

        layout.addView(authSpinner);

        // 用户名输入框
        final EditText usernameInput = new EditText(this);
        usernameInput.setHint("用户名 (企业网络需要)");
        usernameInput.setText(lastUsername); // 恢复上次的用户名
        layout.addView(usernameInput);

        // 密码输入框
        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("密码");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setText(lastPassword); // 恢复上次的密码（如果用户选择保存）
        layout.addView(passwordInput);

        // 保存密码选项
        android.widget.CheckBox savePasswordCheckBox = new android.widget.CheckBox(this);
        savePasswordCheckBox.setText("记住密码");
        savePasswordCheckBox.setChecked(savePassword);
        savePasswordCheckBox.setPadding(0, 20, 0, 10);
        layout.addView(savePasswordCheckBox);

        // 根据认证方式显示/隐藏用户名输入框的方法
        Runnable updateInputVisibility = () -> {
            String selectedAuth = authValues[authSpinner.getSelectedItemPosition()];
            // 只有企业网络(EAP_PEAP, EAP_TTLS)需要用户名
            if ("EAP_PEAP".equals(selectedAuth) || "EAP_TTLS".equals(selectedAuth)) {
                usernameInput.setVisibility(View.VISIBLE);
            } else {
                usernameInput.setVisibility(View.GONE);
            }

            // 开放网络不需要密码
            if ("OPEN".equals(selectedAuth)) {
                passwordInput.setVisibility(View.GONE);
                savePasswordCheckBox.setVisibility(View.GONE);
            } else {
                passwordInput.setVisibility(View.VISIBLE);
                savePasswordCheckBox.setVisibility(View.VISIBLE);
            }
        };

        // 初始化时设置输入框可见性
        updateInputVisibility.run();

        // 根据认证方式显示/隐藏用户名输入框
        authSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateInputVisibility.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                updateInputVisibility.run();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("配置网络")
                .setView(layout)
                .setPositiveButton("确定", (dialogInterface, i) -> {
                    try {
                        String ssid = ssidInput.getText().toString().trim();
                        String auth = authValues[authSpinner.getSelectedItemPosition()];
                        String username = usernameInput.getText().toString().trim();
                        String password = passwordInput.getText().toString().trim();
                        boolean shouldSavePassword = savePasswordCheckBox.isChecked();

                        // 验证必填字段
                        if (TextUtils.isEmpty(ssid)) {
                            Toast.makeText(MainActivity.this, "请输入网络名", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 非开放网络需要密码
                        if (!"OPEN".equals(auth) && TextUtils.isEmpty(password)) {
                            Toast.makeText(MainActivity.this, "请输入密码", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 企业网络需要用户名
                        if (("EAP_PEAP".equals(auth) || "EAP_TTLS".equals(auth)) && TextUtils.isEmpty(username)) {
                            Toast.makeText(MainActivity.this, "企业网络需要输入用户名", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 保存配置到SharedPreferences
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("wifi_last_ssid", ssid);
                        editor.putString("wifi_last_auth", auth);
                        editor.putString("wifi_last_username", username);
                        editor.putBoolean("wifi_save_password", shouldSavePassword);

                        if (shouldSavePassword) {
                            editor.putString("wifi_last_password", password);
                        } else {
                            editor.remove("wifi_last_password");
                        }

                        editor.apply();

                        JSONObject command = new JSONObject();
                        JSONObject wifiData = new JSONObject();
                        wifiData.put("ssid", ssid);
                        wifiData.put("auth", auth);

                        // 企业网络需要用户名
                        if ("EAP_PEAP".equals(auth) || "EAP_TTLS".equals(auth)) {
                            wifiData.put("username", username);
                        }

                        // 只要不是开放网络都需要密码
                        if (!"OPEN".equals(auth)) {
                            wifiData.put("password", password);
                        }

                        wifiData.put("time", System.currentTimeMillis() / 1000.0);
                        command.put("config_wifi", wifiData);

                        sendCommand(command);

                        // 显示配置信息
                        String authName = authOptions[authSpinner.getSelectedItemPosition()];
                        Toast.makeText(MainActivity.this,
                                "正在配置网络: " + ssid + " (" + authName + ")",
                                Toast.LENGTH_SHORT).show();

                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating config wifi command", e);
                        Toast.makeText(MainActivity.this, "配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        dialog.show();
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
                startDiscovery();
            } else {
                ttsManager.showToastWithTTS("需要蓝牙和位置权限才能扫描设备");
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
                ttsManager.showToastWithTTS("应用需要蓝牙功能");
            }
        }
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            // 压缩并上传图片
            executorService.execute(() -> {
                try {
                    Bitmap bitmap = BitmapFactory.decodeFile(currentPhotoPath);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    byte[] imageBytes = baos.toByteArray();
                    String encodedImage = Base64.encodeToString(imageBytes, Base64.DEFAULT);

                    // 上传到服务器
                    uploadImageToServer(encodedImage);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing image", e);
                    mainHandler.post(() ->                         ttsManager.showToastWithTTS("图片处理失败")
                    );
                }
            });
        }
    }

    private void uploadImageToServer(String base64Image) {
        // 显示上传进度

        mainHandler.post(() -> {
            statusText.setText("正在上传图片到服务器...");
            ttsManager.showToastWithTTS("开始上传图片");
        });

        executorService.execute(() -> {
            JSch jsch = new JSch();
            Session session = null;
            ChannelSftp channel = null;

            try {
                // 1. 创建会话
                session = jsch.getSession(SSH_USERNAME, SERVER_IP, 22);
                session.setPassword(SSH_PASSWORD);

                // 避免首次连接时的主机密钥检查
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                session.setConfig(config);

                // 2. 连接会话
                session.connect();

                // 3. 打开SFTP通道
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect();

                // 4. 创建远程目录(如果不存在)
                try {
                    channel.mkdir(REMOTE_PATH);
                } catch (SftpException e) {
                    if (e.id != ChannelSftp.SSH_FX_FAILURE) {
                        throw e;
                    }
                    // 目录已存在，忽略错误
                }
                String patientId = patientIdEdit.getText().toString().trim();

                // 5. 生成唯一文件名
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String remoteFileName;

                if (!TextUtils.isEmpty(patientId)) {
                    remoteFileName = "patient_" + patientId + "_" + timeStamp + ".jpg";
                } else {
                    remoteFileName = "healthmirror_" + timeStamp + ".jpg";
                }
                // 6. 解码Base64并上传
                byte[] imageData = Base64.decode(base64Image, Base64.DEFAULT);
                try (InputStream inputStream = new ByteArrayInputStream(imageData)) {
                    channel.put(inputStream, REMOTE_PATH + remoteFileName);

                    // 7. 上传成功处理
                    mainHandler.post(() -> {
                        statusText.setText("图片上传成功: " + remoteFileName);
                        ttsManager.showToastWithTTS("图片上传成功");

                    });

                }
            } catch (Exception e) {
                Log.e(TAG, "Error uploading image", e);
                mainHandler.post(() -> {
                    statusText.setText("上传失败: " + e.getMessage());
                    ttsManager.showToastWithTTS("上传失败");

                });
            } finally {
                // 9. 关闭连接
                if (channel != null) {
                    channel.disconnect();
                }
                if (session != null) {
                    session.disconnect();
                }
            }
        });
    }
    private void toggleTTS() {
        boolean enabled = !ttsManager.isEnabled();
        ttsManager.setEnabled(enabled);
        ttsManager.showToastWithTTS(enabled ? "语音播报已开启" : "语音播报已关闭");
    }
}