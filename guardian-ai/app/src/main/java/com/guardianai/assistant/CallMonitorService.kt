package com.guardianai.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianai.assistant.ai.VoicePhishingDetector
import kotlinx.coroutines.launch

/**
 * CallMonitorService - 통화 상태 모니터링 및 보이스피싱 감지 서비스
 *
 * 전화 수신/발신을 감지하고, 통화 중 음성 인식(STT)을 통해
 * 실시간으로 보이스피싱 키워드를 분석합니다.
 *
 * 동작 과정:
 * 1. PhoneStateReceiver로 통화 상태 변화 감지
 * 2. 통화 시작 시 발신번호 분석 (미등록 번호 경고)
 * 3. 통화 중 SpeechRecognizer로 음성→텍스트 변환
 * 4. VoicePhishingDetector로 텍스트 분석
 * 5. 위험 감지 시 즉시 경고 알림
 */
class CallMonitorService : LifecycleService() {

    companion object {
        private const val TAG = "CallMonitorService"

        const val ACTION_CALL_STATUS = "com.guardianai.ACTION_CALL_STATUS"
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_RISK_LEVEL = "risk_level"

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }

    private lateinit var phishingDetector: VoicePhishingDetector
    private lateinit var notificationHelper: NotificationHelper
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInCall = false
    private var currentPhoneNumber: String = ""

    /** 전화 상태 변화 감지 리시버 */
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // 전화 수신
                    currentPhoneNumber = number
                    Log.d(TAG, "전화 수신: $number")
                    phishingDetector.onCallStarted(number)
                    broadcastCallStatus("RINGING", number, 0)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // 통화 시작
                    isInCall = true
                    Log.d(TAG, "통화 시작")
                    startSpeechRecognition()
                    broadcastCallStatus("IN_CALL", currentPhoneNumber, 0)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // 통화 종료
                    if (isInCall) {
                        isInCall = false
                        Log.d(TAG, "통화 종료")
                        stopSpeechRecognition()
                        lifecycleScope.launch {
                            phishingDetector.onCallEnded()
                        }
                        broadcastCallStatus("IDLE", "", 0)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "통화 모니터링 서비스 생성")

        notificationHelper = NotificationHelper(this)
        phishingDetector = VoicePhishingDetector(this, notificationHelper)

        // 전화 상태 리시버 등록
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(phoneStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "통화 모니터링 시작")

        startForeground(
            NotificationHelper.NOTIFICATION_ID_FOREGROUND + 3,
            notificationHelper.createForegroundNotification("보이스피싱 감시 중")
        )

        return START_STICKY
    }

    /**
     * 음성 인식 시작 - 통화 중 실시간 STT
     */
    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "음성 인식을 사용할 수 없습니다")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = texts?.firstOrNull() ?: return

                Log.d(TAG, "음성 인식: $transcript")

                // 보이스피싱 분석
                val result = phishingDetector.analyzeTranscript(transcript)
                if (result.riskLevel > 0) {
                    broadcastCallStatus("WARNING", currentPhoneNumber, result.riskLevel)
                }

                // 통화 중이면 다시 인식 시작 (연속 인식)
                if (isInCall) {
                    startListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = texts?.firstOrNull() ?: return
                // 부분 결과도 분석
                phishingDetector.analyzeTranscript(transcript)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "음성 인식 오류: $error")
                // 통화 중이면 재시도
                if (isInCall) {
                    startListening()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "음성 인식 시작 실패: ${e.message}")
        }
    }

    private fun stopSpeechRecognition() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "음성 인식 중지 오류: ${e.message}")
        }
    }

    private fun broadcastCallStatus(state: String, phoneNumber: String, riskLevel: Int) {
        val intent = Intent(ACTION_CALL_STATUS).apply {
            putExtra(EXTRA_CALL_STATE, state)
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(EXTRA_RISK_LEVEL, riskLevel)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "통화 모니터링 서비스 종료")
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) { }
        stopSpeechRecognition()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
