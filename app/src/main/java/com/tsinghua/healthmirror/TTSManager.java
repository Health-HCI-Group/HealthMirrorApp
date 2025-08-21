package com.tsinghua.healthmirror;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Locale;

/**
 * TTS语音播报管理类
 * 提供文本转语音功能，包括Toast消息的语音播报
 */
public class TTSManager {
    private static final String TAG = "TTSManager";
    private static TTSManager instance;

    private Context context;
    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;
    private boolean isEnabled = false; // 是否启用TTS功能

    // TTS设置
    private float speechRate = 0.8f; // 语速
    private float pitch = 1.0f;      // 音调

    /**
     * 获取TTSManager单例
     */
    public static TTSManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TTSManager.class) {
                if (instance == null) {
                    instance = new TTSManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 私有构造函数
     */
    private TTSManager(Context context) {
        this.context = context;
        initTextToSpeech();
    }

    /**
     * 初始化文本转语音引擎
     */
    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    // 设置语言为中文，失败则使用英文
                    int result = textToSpeech.setLanguage(Locale.CHINESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "中文TTS不支持，使用英文");
                        textToSpeech.setLanguage(Locale.US);
                    }

                    // 设置语速和音调
                    textToSpeech.setSpeechRate(speechRate);
                    textToSpeech.setPitch(pitch);

                    isTtsReady = true;
                    Log.d(TAG, "TTS初始化成功");

                    // 设置播放进度监听
                    setTtsProgressListener();

                } else {
                    Log.e(TAG, "TTS初始化失败");
                    isTtsReady = false;
                }
            }
        });
    }

    /**
     * 设置TTS播放进度监听器
     */
    private void setTtsProgressListener() {
        if (textToSpeech != null) {
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "TTS开始播放: " + utteranceId);
                }

                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "TTS播放完成: " + utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "TTS播放错误: " + utteranceId);
                }
            });
        }
    }

    /**
     * 显示Toast并同时播报内容
     * @param message 要显示和播报的消息
     * @param duration Toast显示时长
     */
    public void showToastWithTTS(String message, int duration) {
        // 显示Toast
        Toast.makeText(context, message, duration).show();

        // 播报内容
        if (isEnabled) {
            speak(message);
        }
    }

    /**
     * 显示短时间Toast并播报
     * @param message 消息内容
     */
    public void showToastWithTTS(String message) {
        showToastWithTTS(message, Toast.LENGTH_SHORT);
    }

    /**
     * 只播报文本，不显示Toast
     * @param text 要播报的文本
     */
    public void speak(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH);
    }

    /**
     * 播报文本
     * @param text 要播报的文本
     * @param queueMode 队列模式 QUEUE_FLUSH(清空队列) 或 QUEUE_ADD(添加到队列)
     */
    public void speak(String text, int queueMode) {
        if (!isEnabled || !isTtsReady) {
            return;
        }

        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Log.d(TAG, "播报: " + text);

        // Android API 21以上使用新的方法
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            String utteranceId = "tts_" + System.currentTimeMillis();
            textToSpeech.speak(text, queueMode, null, utteranceId);
        } else {
            // Android API 21以下使用旧方法
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_" + System.currentTimeMillis());
            textToSpeech.speak(text, queueMode, params);
        }
    }

    /**
     * 添加到播报队列（不清空当前播报）
     * @param text 要播报的文本
     */
    public void speakQueue(String text) {
        speak(text, TextToSpeech.QUEUE_ADD);
    }

    /**
     * 停止TTS播放
     */
    public void stop() {
        if (textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
            Log.d(TAG, "TTS播放已停止");
        }
    }

    /**
     * 检查是否正在播放
     */
    public boolean isSpeaking() {
        return textToSpeech != null && textToSpeech.isSpeaking();
    }

    /**
     * 启用或禁用TTS功能
     * @param enabled true启用，false禁用
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled && isSpeaking()) {
            stop();
        }
        Log.d(TAG, "TTS功能" + (enabled ? "启用" : "禁用"));
    }

    /**
     * 检查TTS是否启用
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * 检查TTS是否准备就绪
     */
    public boolean isReady() {
        return isTtsReady;
    }

    /**
     * 设置语速
     * @param rate 语速 (0.5f - 2.0f, 1.0f为正常速度)
     */
    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (textToSpeech != null) {
            textToSpeech.setSpeechRate(rate);
        }
    }

    /**
     * 获取当前语速
     */
    public float getSpeechRate() {
        return speechRate;
    }

    /**
     * 设置音调
     * @param pitch 音调 (0.5f - 2.0f, 1.0f为正常音调)
     */
    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (textToSpeech != null) {
            textToSpeech.setPitch(pitch);
        }
    }

    /**
     * 获取当前音调
     */
    public float getPitch() {
        return pitch;
    }

    // ========== 业务相关的便捷方法 ==========

    /**
     * 播报连接状态
     */
    public void speakConnectionStatus(boolean connected, String deviceName) {
        String message = connected ?
                "设备" + deviceName + "连接成功" :
                "设备连接断开";
        speak(message);
    }

    /**
     * 播报数据采集状态
     */
    public void speakCaptureStatus(boolean started, int duration) {
        String message = started ?
                "开始数据采集，持续" + duration + "秒" :
                "数据采集已完成";
        speak(message);
    }

    /**
     * 播报倒计时
     */
    public void speakCountdown(int remainingSeconds) {
        if (remainingSeconds <= 10 && remainingSeconds > 0) {
            speak(String.valueOf(remainingSeconds));
        } else if (remainingSeconds == 0) {
            speak("采集完成");
        }
    }

    /**
     * 播报错误信息
     */
    public void speakError(String errorMessage) {
        speak("错误：" + errorMessage);
    }

    /**
     * 播报成功信息
     */
    public void speakSuccess(String successMessage) {
        speak("成功：" + successMessage);
    }

    /**
     * 播报警告信息
     */
    public void speakWarning(String warningMessage) {
        speak("警告：" + warningMessage);
    }

    /**
     * 释放TTS资源
     * 通常在Application的onTerminate中调用
     */
    public void release() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
            isTtsReady = false;
            Log.d(TAG, "TTS资源已释放");
        }
        instance = null;
    }
}