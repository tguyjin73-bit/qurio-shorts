package com.guardianai.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianai.assistant.data.ActivityLogRepository
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * CameraMonitorService - CameraX 기반 영상 모니터링 포그라운드 서비스
 *
 * 전면/후면 카메라를 통해 영상 프레임을 분석하여
 * 급격한 밝기 변화(낙상 시 화면 급변), 장시간 무변화(의식 상실 의심) 등을 감지합니다.
 * 프라이버시 보호를 위해 영상은 저장하지 않고 밝기 분석만 수행합니다.
 */
class CameraMonitorService : LifecycleService() {

    companion object {
        private const val TAG = "GuardianCameraService"

        /** 밝기 급변 임계값 (프레임 간 평균 밝기 차이) */
        private const val BRIGHTNESS_CHANGE_THRESHOLD = 80

        /** 장시간 무변화 임계값 (밀리초, 3분) */
        private const val NO_CHANGE_THRESHOLD_MS = 3 * 60 * 1000L

        /** 분석 간격 (밀리초, 500ms마다 1프레임) */
        private const val ANALYSIS_INTERVAL_MS = 500L

        /** 알림 쿨다운 (60초) */
        private const val NOTIFICATION_COOLDOWN_MS = 60_000L

        /** 카메라 상태 브로드캐스트 */
        const val ACTION_CAMERA_UPDATE = "com.guardianai.ACTION_CAMERA_UPDATE"
        const val EXTRA_CAMERA_STATUS = "camera_status"
        const val EXTRA_BRIGHTNESS = "brightness"

        fun start(context: Context) {
            val intent = Intent(context, CameraMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraMonitorService::class.java))
        }
    }

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var repository: ActivityLogRepository
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var previousBrightness: Double = -1.0
    private var lastChangeTimestamp: Long = 0L
    private var lastNotificationTime: Long = 0L
    private var lastAnalysisTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "카메라 모니터링 서비스 생성")

        notificationHelper = NotificationHelper(this)
        repository = ActivityLogRepository(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "카메라 모니터링 시작")

        startForeground(
            NotificationHelper.NOTIFICATION_ID_FOREGROUND + 2,
            notificationHelper.createForegroundNotification("카메라 모니터링 중")
        )

        startCamera()
        return START_STICKY
    }

    /**
     * CameraX를 시작하고 ImageAnalysis를 바인딩
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // 이미지 분석 설정 (저해상도로 성능 최적화)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                // 전면 카메라 사용 (노인 모니터링에 적합)
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // 기존 바인딩 해제 후 새로 바인딩
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)

                lastChangeTimestamp = System.currentTimeMillis()
                sendCameraBroadcast("활성", 0.0)
                Log.d(TAG, "카메라 바인딩 성공")

            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패: ${e.message}")
                sendCameraBroadcast("오류", 0.0)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 프레임 분석 - 평균 밝기를 계산하여 급변/무변화 감지
     * 프라이버시: 영상 데이터는 저장하지 않고 밝기 수치만 분석
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // 분석 간격 제어 (배터리 절약)
        if (currentTime - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = currentTime

        try {
            // Y 평면에서 평균 밝기 계산
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var sum = 0L
            for (b in bytes) {
                sum += (b.toInt() and 0xFF)
            }
            val avgBrightness = sum.toDouble() / bytes.size

            // 밝기 급변 감지
            if (previousBrightness >= 0) {
                val brightnessChange = abs(avgBrightness - previousBrightness)

                if (brightnessChange > BRIGHTNESS_CHANGE_THRESHOLD) {
                    Log.w(TAG, "밝기 급변 감지: %.1f → %.1f (변화: %.1f)".format(
                        previousBrightness, avgBrightness, brightnessChange
                    ))
                    onBrightnessAnomaly("밝기 급변", brightnessChange, currentTime)
                    lastChangeTimestamp = currentTime
                } else if (brightnessChange > 5) {
                    // 약간의 변화라도 있으면 타임스탬프 갱신
                    lastChangeTimestamp = currentTime
                }
            } else {
                lastChangeTimestamp = currentTime
            }

            // 장시간 무변화 감지 (카메라가 뒤집어져 있거나 의식 상실)
            if (currentTime - lastChangeTimestamp > NO_CHANGE_THRESHOLD_MS) {
                onBrightnessAnomaly("장시간 영상 무변화", 0.0, currentTime)
                lastChangeTimestamp = currentTime // 리셋
            }

            previousBrightness = avgBrightness

        } catch (e: Exception) {
            Log.e(TAG, "프레임 분석 오류: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 영상 이상 감지 시 알림 및 DB 기록
     */
    private fun onBrightnessAnomaly(type: String, value: Double, currentTime: Long) {
        if (currentTime - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) return
        lastNotificationTime = currentTime

        val message = "카메라 $type 감지 (변화량: %.1f)".format(value)

        notificationHelper.sendEmergencyNotification(
            title = "⚠ 영상 이상 감지",
            message = message
        )

        lifecycleScope.launch {
            repository.saveActivityLog(
                activityType = "CAMERA_ANOMALY",
                confidence = 70,
                isAnomaly = true,
                anomalyDescription = message
            )
        }

        sendCameraBroadcast("이상 감지", value)
    }

    private fun sendCameraBroadcast(status: String, brightness: Double) {
        val intent = Intent(ACTION_CAMERA_UPDATE).apply {
            putExtra(EXTRA_CAMERA_STATUS, status)
            putExtra(EXTRA_BRIGHTNESS, brightness)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "카메라 모니터링 서비스 종료")
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
