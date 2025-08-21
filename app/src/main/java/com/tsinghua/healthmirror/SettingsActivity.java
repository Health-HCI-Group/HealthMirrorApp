package com.tsinghua.healthmirror;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "HealthMirrorSettings";
    private static final String KEY_DEFAULT_DURATION = "default_duration";
    private static final String KEY_CUSTOM_TAGS = "custom_tags";
    private static final int DEFAULT_CAPTURE_DURATION = 60;

    private EditText defaultDurationEdit;
    private EditText newTagNameEdit, newTagUnitEdit;
    private LinearLayout defaultTagsContainer, customTagsContainer;
    private SharedPreferences prefs;
    private List<HealthTag> customTags;

    // 健康指标标签数据类
    public static class HealthTag {
        public String name;
        public String unit;
        public String key;
        public boolean isDefault;

        public HealthTag(String name, String unit, String key, boolean isDefault) {
            this.name = name;
            this.unit = unit;
            this.key = key;
            this.isDefault = isDefault;
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("unit", unit);
            json.put("key", key);
            json.put("isDefault", isDefault);
            return json;
        }

        public static HealthTag fromJSON(JSONObject json) throws JSONException {
            return new HealthTag(
                    json.getString("name"),
                    json.getString("unit"),
                    json.getString("key"),
                    json.getBoolean("isDefault")
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        initData();
        loadSettings();
        setupListeners();
        refreshTagsDisplay();
    }

    private void initViews() {
        defaultDurationEdit = findViewById(R.id.defaultDurationEdit);
        newTagNameEdit = findViewById(R.id.newTagNameEdit);
        newTagUnitEdit = findViewById(R.id.newTagUnitEdit);
        defaultTagsContainer = findViewById(R.id.defaultTagsContainer);
        customTagsContainer = findViewById(R.id.customTagsContainer);
    }

    private void initData() {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        customTags = new ArrayList<>();

        // 初始化系统默认标签
        initDefaultTags();
    }

    private void initDefaultTags() {
        List<HealthTag> defaultTags = getDefaultTags();

        for (HealthTag tag : defaultTags) {
            View tagView = createTagView(tag, false); // false表示不可删除
            defaultTagsContainer.addView(tagView);
        }
    }

    public static List<HealthTag> getDefaultTags() {
        List<HealthTag> defaultTags = new ArrayList<>();
        defaultTags.add(new HealthTag("收缩压", "mmHg", "bloodPressureSystolic", true));
        defaultTags.add(new HealthTag("舒张压", "mmHg", "bloodPressureDiastolic", true));
        defaultTags.add(new HealthTag("心率", "bpm", "heartRate", true));
        defaultTags.add(new HealthTag("体温", "℃", "bodyTemp", true));
        defaultTags.add(new HealthTag("血氧饱和度", "%", "bloodOxygen", true));
        defaultTags.add(new HealthTag("呼吸率", "bpm", "respiratoryRate", true));
        defaultTags.add(new HealthTag("医生备注", "", "medicalNotes", true));
        return defaultTags;
    }

    private void loadSettings() {
        // 加载默认采集时长
        int defaultDuration = prefs.getInt(KEY_DEFAULT_DURATION, DEFAULT_CAPTURE_DURATION);
        defaultDurationEdit.setText(String.valueOf(defaultDuration));

        // 加载自定义标签
        String customTagsJson = prefs.getString(KEY_CUSTOM_TAGS, "[]");
        try {
            JSONArray jsonArray = new JSONArray(customTagsJson);
            customTags.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject tagJson = jsonArray.getJSONObject(i);
                customTags.add(HealthTag.fromJSON(tagJson));
            }
        } catch (JSONException e) {
            e.printStackTrace();
            customTags.clear();
        }
    }

    private void setupListeners() {
        // 返回按钮
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // 保存采集时长
        findViewById(R.id.saveDurationButton).setOnClickListener(v -> saveDuration());

        // 添加标签
        findViewById(R.id.addTagButton).setOnClickListener(v -> addCustomTag());

        // 重置设置
        findViewById(R.id.resetButton).setOnClickListener(v -> showResetDialog());
    }

    private void saveDuration() {
        String durationStr = defaultDurationEdit.getText().toString().trim();
        if (TextUtils.isEmpty(durationStr)) {
            Toast.makeText(this, "请输入采集时长", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int duration = Integer.parseInt(durationStr);
            if (duration <= 0) {
                Toast.makeText(this, "采集时长必须大于0", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putInt(KEY_DEFAULT_DURATION, duration).apply();
            Toast.makeText(this, "默认采集时长已保存", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
        }
    }

    private void addCustomTag() {
        String tagName = newTagNameEdit.getText().toString().trim();
        String tagUnit = newTagUnitEdit.getText().toString().trim();

        if (TextUtils.isEmpty(tagName)) {
            Toast.makeText(this, "请输入标签名称", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查是否已存在相同名称的标签
        for (HealthTag tag : customTags) {
            if (tag.name.equals(tagName)) {
                Toast.makeText(this, "标签名称已存在", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 生成唯一的key
        String key = "custom_" + System.currentTimeMillis();

        // 创建新标签
        HealthTag newTag = new HealthTag(tagName, tagUnit, key, false);
        customTags.add(newTag);

        // 保存到SharedPreferences
        saveCustomTags();

        // 刷新显示
        refreshTagsDisplay();

        // 清空输入框
        newTagNameEdit.setText("");
        newTagUnitEdit.setText("");

        Toast.makeText(this, "标签添加成功", Toast.LENGTH_SHORT).show();
    }

    private void saveCustomTags() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (HealthTag tag : customTags) {
                jsonArray.put(tag.toJSON());
            }
            prefs.edit().putString(KEY_CUSTOM_TAGS, jsonArray.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void refreshTagsDisplay() {
        // 清空现有的自定义标签显示
        customTagsContainer.removeAllViews();

        // 添加自定义标签
        for (HealthTag tag : customTags) {
            View tagView = createTagView(tag, true); // true表示可删除
            customTagsContainer.addView(tagView);
        }

        // 如果没有自定义标签，显示提示
        if (customTags.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("暂无自定义指标");
            emptyView.setTextColor(getResources().getColor(android.R.color.darker_gray));
            emptyView.setPadding(16, 8, 16, 8);
            customTagsContainer.addView(emptyView);
        }
    }

    private View createTagView(HealthTag tag, boolean canDelete) {
        View tagView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null);

        // 创建自定义布局
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(16, 12, 16, 12);
        container.setBackgroundColor(getResources().getColor(android.R.color.white));

        // 标签信息
        LinearLayout infoContainer = new LinearLayout(this);
        infoContainer.setOrientation(LinearLayout.VERTICAL);
        infoContainer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView nameText = new TextView(this);
        nameText.setText(tag.name);
        nameText.setTextSize(16);
        nameText.setTextColor(getResources().getColor(android.R.color.black));

        TextView unitText = new TextView(this);
        String unitDisplay = TextUtils.isEmpty(tag.unit) ? "无单位" : "单位: " + tag.unit;
        unitText.setText(unitDisplay);
        unitText.setTextSize(12);
        unitText.setTextColor(getResources().getColor(android.R.color.darker_gray));

        infoContainer.addView(nameText);
        infoContainer.addView(unitText);
        container.addView(infoContainer);

        // 删除按钮（仅自定义标签显示）
        if (canDelete) {
            Button deleteButton = new Button(this);
            deleteButton.setText("删除");
            deleteButton.setTextSize(12);
            deleteButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
            deleteButton.setTextColor(getResources().getColor(android.R.color.white));
            deleteButton.setPadding(24, 8, 24, 8);

            deleteButton.setOnClickListener(v -> showDeleteConfirmDialog(tag));
            container.addView(deleteButton);
        }

        // 添加分割线
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(container);
        wrapper.addView(divider);

        return wrapper;
    }

    private void showDeleteConfirmDialog(HealthTag tag) {
        new AlertDialog.Builder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除标签 \"" + tag.name + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    customTags.remove(tag);
                    saveCustomTags();
                    refreshTagsDisplay();
                    Toast.makeText(SettingsActivity.this, "标签已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showResetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("重置确认")
                .setMessage("确定要重置所有设置吗？这将清除所有自定义标签并恢复默认采集时长。")
                .setPositiveButton("重置", (dialog, which) -> {
                    // 清除所有自定义设置
                    prefs.edit().clear().apply();

                    // 重新加载默认设置
                    customTags.clear();
                    defaultDurationEdit.setText(String.valueOf(DEFAULT_CAPTURE_DURATION));
                    refreshTagsDisplay();

                    Toast.makeText(SettingsActivity.this, "设置已重置", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 静态方法供MainActivity调用
    public static int getDefaultCaptureDuration(SharedPreferences prefs) {
        return prefs.getInt(KEY_DEFAULT_DURATION, DEFAULT_CAPTURE_DURATION);
    }

    public static List<HealthTag> getAllTags(SharedPreferences prefs) {
        List<HealthTag> allTags = new ArrayList<>();

        // 添加系统默认标签
        allTags.addAll(getDefaultTags());

        // 添加自定义标签
        String customTagsJson = prefs.getString(KEY_CUSTOM_TAGS, "[]");
        try {
            JSONArray jsonArray = new JSONArray(customTagsJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject tagJson = jsonArray.getJSONObject(i);
                allTags.add(HealthTag.fromJSON(tagJson));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return allTags;
    }
}