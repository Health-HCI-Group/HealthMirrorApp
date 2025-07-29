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
import java.util.Locale;
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
    private int remainingCaptureTime = 0; // 剩余采集时间(秒)
    private Handler captureHandler = new Handler();
    private Runnable captureTimer;

    private ListView deviceListView;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayList<String> deviceList;
    private ArrayList<BluetoothDevice> bluetoothDeviceList;

    private TextView statusText, deviceInfoText;
    private EditText patientIdEdit, patientHealthIndexEdit, patientCustomInfoEdit;
    private Button scanButton, connectButton, syncTimeButton;
    private Button startCaptureButton, stopCaptureButton, refreshInfoButton;
    private Button configWifiButton, capturePhotoButton;
    private ProgressBar captureProgressBar;
    private TextView captureTimeText;

    private Handler mainHandler;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String currentPhotoPath;

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
        patientIdEdit = findViewById(R.id.patientIdEdit);
        patientCustomInfoEdit = findViewById(R.id.patientCustomInfoEdit);
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        syncTimeButton = findViewById(R.id.syncTimeButton);
        startCaptureButton = findViewById(R.id.startCaptureButton);
        stopCaptureButton = findViewById(R.id.stopCaptureButton);
        refreshInfoButton = findViewById(R.id.refreshInfoButton);
        configWifiButton = findViewById(R.id.configWifiButton);
        capturePhotoButton = findViewById(R.id.capturePhotoButton);

        deviceList = new ArrayList<>();
        bluetoothDeviceList = new ArrayList<>();
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        deviceListView.setAdapter(deviceAdapter);
        captureProgressBar = findViewById(R.id.captureProgressBar);
        captureTimeText = findViewById(R.id.captureTimeText);
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
                return; // Wait for permission result
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
        startCaptureButton.setOnClickListener(v -> showCaptureDurationDialog());
        stopCaptureButton.setOnClickListener(v -> stopCapture());
        refreshInfoButton.setOnClickListener(v -> refreshInfo());
        configWifiButton.setOnClickListener(v -> configWifi());
        capturePhotoButton.setOnClickListener(v -> dispatchTakePictureIntent());

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < bluetoothDeviceList.size()) {
                connectedDevice = bluetoothDeviceList.get(position);
                statusText.setText("Selected: " + connectedDevice.getName());
            }
        });
    }
    private void showCaptureDurationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置采集时长");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("输入采集时长(秒)");
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            try {
                int duration = Integer.parseInt(input.getText().toString());
                if (duration > 0) {
                    startCaptureWithDuration(duration);
                } else {
                    Toast.makeText(this, "请输入有效的采集时长", Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    // 在startCaptureWithDuration方法中替换健康指标收集部分：
    private void startCaptureWithDuration(int durationSeconds) {
        // 获取基础患者信息
        String patientId = patientIdEdit.getText().toString().trim();

        // 获取健康指标
        String bpSystolic = ((EditText)findViewById(R.id.bloodPressureSystolic)).getText().toString();
        String bpDiastolic = ((EditText)findViewById(R.id.bloodPressureDiastolic)).getText().toString();
        String heartRate = ((EditText)findViewById(R.id.heartRate)).getText().toString();
        String bodyTemp = ((EditText)findViewById(R.id.bodyTemp)).getText().toString();
        String bloodOxygen = ((EditText)findViewById(R.id.bloodOxygen)).getText().toString();
        String medicalNotes = ((EditText)findViewById(R.id.medicalNotes)).getText().toString();

        // 构建结构化健康数据
        JSONObject healthData = new JSONObject();
        try {
            // 基础信息
            if(!patientId.isEmpty()) healthData.put("patient_id", patientId);

            // 生命体征
            JSONObject vitals = new JSONObject();
            if(!bpSystolic.isEmpty() && !bpDiastolic.isEmpty()) {
                vitals.put("blood_pressure", bpSystolic + "/" + bpDiastolic);
            }
            if(!heartRate.isEmpty()) vitals.put("heart_rate", heartRate + "bpm");
            if(!bodyTemp.isEmpty()) vitals.put("temperature", bodyTemp + "℃");
            if(!bloodOxygen.isEmpty()) vitals.put("blood_oxygen", bloodOxygen + "%");

            healthData.put("vitals", vitals);
            if(!medicalNotes.isEmpty()) healthData.put("notes", medicalNotes);

            // 发送数据
            JSONObject command = new JSONObject();
            command.put("start_capture", new JSONObject()
                    .put("patient_info", healthData.toString())
                    .put("duration", durationSeconds)
                    .put("time", System.currentTimeMillis() / 1000.0));

            sendCommand(command);
            startCaptureTimer(durationSeconds);
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
        // 显示进度条
        captureProgressBar.setMax(totalSeconds);
        captureProgressBar.setProgress(totalSeconds);
        captureProgressBar.setVisibility(View.VISIBLE);
        captureTimeText.setVisibility(View.VISIBLE);
        captureTimeText.setText(formatTime(remainingCaptureTime));

        // 禁用开始按钮，启用停止按钮
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

        // 停止计时器
        if (captureTimer != null) {
            captureHandler.removeCallbacks(captureTimer);
        }

        isCapturing = false;
        captureProgressBar.setVisibility(View.GONE);
        captureTimeText.setVisibility(View.GONE);

        // 恢复按钮状态
        startCaptureButton.setEnabled(true);
        stopCaptureButton.setEnabled(false);
    }
    // 格式化时间为 MM:SS
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
                    imageFileName,  /* prefix */
                    ".jpg",         /* suffix */
                    storageDir      /* directory */
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
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(MainActivity.this, "Connected successfully!", Toast.LENGTH_SHORT).show();
                });
                Log.d(TAG, "BluetoothSocket isConnected: " + bluetoothSocket.isConnected());

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
        if (!isConnected || outputStream == null) {
            Toast.makeText(this, "Not connected to device", Toast.LENGTH_SHORT).show();
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
        // 创建输入框
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText ssidInput = new EditText(this);
        ssidInput.setHint("SSID");
        layout.addView(ssidInput);

        final EditText authInput = new EditText(this);
        authInput.setHint("Auth (OPEN / WPA2_PSK / EAP_PEAP / EAP_TTLS)");
        layout.addView(authInput);

        final EditText usernameInput = new EditText(this);
        usernameInput.setHint("Username (if required)");
        layout.addView(usernameInput);

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passwordInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Configure WiFi")
                .setView(layout)
                .setPositiveButton("OK", (dialogInterface, i) -> {
                    try {
                        String ssid = ssidInput.getText().toString();
                        String auth = authInput.getText().toString();
                        String username = usernameInput.getText().toString();
                        String password = passwordInput.getText().toString();

                        JSONObject command = new JSONObject();
                        JSONObject wifiData = new JSONObject();
                        wifiData.put("ssid", ssid);
                        wifiData.put("auth", auth);

                        if (!auth.equals("OPEN") && !auth.equals("WPA2_PSK")) {
                            wifiData.put("username", username);
                        }

                        // 只要不是 OPEN 都需要密码
                        if (!auth.equals("OPEN")) {
                            wifiData.put("password", password);
                        }

                        wifiData.put("time", System.currentTimeMillis() / 1000.0);
                        command.put("config_wifi", wifiData);

                        sendCommand(command);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating config wifi command", e);
                    }
                })
                .setNegativeButton("Cancel", null)
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
                Toast.makeText(this, "需要蓝牙和位置权限才能扫描设备", Toast.LENGTH_SHORT).show();
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
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                            "图片处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        }
    }
    private void uploadImageToServer(String base64Image) {
        // 显示上传进度
        mainHandler.post(() -> {
            statusText.setText("正在上传图片到服务器...");
            Toast.makeText(MainActivity.this, "开始上传图片", Toast.LENGTH_SHORT).show();
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

                // 5. 生成唯一文件名
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String remoteFileName = "healthmirror_" + timeStamp + ".jpg";

                // 6. 解码Base64并上传
                byte[] imageData = Base64.decode(base64Image, Base64.DEFAULT);
                try (InputStream inputStream = new ByteArrayInputStream(imageData)) {
                    channel.put(inputStream, REMOTE_PATH + remoteFileName);

                    // 7. 上传成功处理
                    mainHandler.post(() -> {
                        statusText.setText("图片上传成功: " + remoteFileName);
                        Toast.makeText(MainActivity.this,
                                "图片上传成功!", Toast.LENGTH_SHORT).show();
                    });

                }
            } catch (Exception e) {
                Log.e(TAG, "Error uploading image", e);
                mainHandler.post(() -> {
                    statusText.setText("上传失败: " + e.getMessage());
                    Toast.makeText(MainActivity.this,
                            "上传失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
}