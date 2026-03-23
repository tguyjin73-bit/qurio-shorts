package com.guardianai.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianai.assistant.data.ActivityLogRepository
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * SensorMonitorService - 가속도계/자이로스코프 기반 낙상 감지 포그라운드 서비스
 *
 * 휴대폰의 가속도 센서와 자이로스코프를 실시간으로 모니터링하여
 * 급격한 가속도 변화(자유낙하 → 충격)를 감지하고 낙상 의심 알림을 발송합니다.
 */
class SensorMonitorService : LifecycleService(), SensorEventListener {

    companion object {
        private const val TAG = "GuardianSensorService"

        /** 자유낙하 감지 임계값 (중력가속도보다 훨씬 낮은 값) */
        private const val FREEFALL_THRESHOLD = 3.0f

        /** 충격 감지 임계값 (중력가속도의 약 3배) */
        private const val IMPACT_THRESHOLD = 25.0f

        /** 자유낙하 후 충격까지 허용 시간 윈도우 (1초) */
        private const val FALL_WINDOW_MS = 1000L

        /** 낙상 알림 쿨다운 (30초) */
        private const val NOTIFICATION_COOLDOWN_MS = 30_000L

        /** 센서 데이터를 외부에 브로드캐스트하는 액션 */
        const val ACTION_SENSOR_UPDATE = "com.guardianai.ACTION_SENSOR_UPDATE"
        const val EXTRA_ACCEL_X = "accel_x"
        const val EXTRA_ACCEL_Y = "accel_y"
        const val EXTRA_ACCEL_Z = "accel_z"
        const val EXTRA_ACCEL_MAGNITUDE = "accel_magnitude"
        const val EXTRA_FALL_DETECTED = "fall_detected"

        fun start(context: Context) {
            val intent = Intent(context, SensorMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorMonitorService::class.java))
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var repository: ActivityLogRepository

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    /** 자유낙하 감지 시각 */
    private var freefallTimestamp: Long = 0L
    private var freefallDetected = false

    /** 마지막 낙상 알림 시각 */
    private var lastFallNotificationTime: Long = 0L

    /** 현재 자이로스코프 회전 속도 */
    private var currentGyroMagnitude: Float = 0f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "센서 모니터링 서비스 생성")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationHelper = NotificationHelper(this)
        repository = ActivityLogRepository(this)

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "센서 모니터링 시작")

        startForeground(
            NotificationHelper.NOTIFICATION_ID_FOREGROUND + 1,
            notificationHelper.createForegroundNotification("센서 모니터링 중")
        )

        // 가속도계 등록 (SENSOR_DELAY_GAME ≈ 20ms 간격)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "가속도계 등록 완료")
        } ?: Log.w(TAG, "가속도계 센서를 사용할 수 없습니다")

        // 자이로스코프 등록
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "자이로스코프 등록 완료")
        } ?: Log.w(TAG, "자이로스코프 센서를 사용할 수 없습니다")

        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
        }
    }

    /**
     * 가속도계 데이터 처리 - 자유낙하/충격 감지 알고리즘
     *
     * 낙상 감지 원리:
     * 1단계: 자유낙하 시 가속도 크기가 급격히 감소 (거의 0에 가까움)
     * 2단계: 바닥 충돌 시 가속도 크기가 급격히 증가 (큰 충격)
     * 1→2가 짧은 시간(1초) 내에 발생하면 낙상으로 판정
     */
    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val currentTime = System.currentTimeMillis()

        // 1단계: 자유낙하 감지 (가속도가 매우 낮아짐)
        if (magnitude < FREEFALL_THRESHOLD && !freefallDetected) {
            freefallDetected = true
            freefallTimestamp = currentTime
            Log.d(TAG, "자유낙하 감지! magnitude=$magnitude")
        }

        // 2단계: 충격 감지 (자유낙하 후 큰 가속도)
        if (freefallDetected && magnitude > IMPACT_THRESHOLD) {
            val timeSinceFreefall = currentTime - freefallTimestamp
            if (timeSinceFreefall <= FALL_WINDOW_MS) {
                Log.w(TAG, "낙상 감지! 자유낙하→충격 (${timeSinceFreefall}ms, magnitude=$magnitude)")
                onFallDetected(magnitude, currentTime)
            }
            freefallDetected = false
        }

        // 자유낙하 윈도우 초과 시 리셋
        if (freefallDetected && (currentTime - freefallTimestamp) > FALL_WINDOW_MS) {
            freefallDetected = false
        }

        // UI 업데이트 브로드캐스트 (100ms마다 한 번)
        if (currentTime % 100 < 20) {
            sendSensorBroadcast(x, y, z, magnitude, false)
        }
    }

    private fun handleGyroscope(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        currentGyroMagnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    /**
     * 낙상 감지 시 호출 - 알림 발송 및 DB 기록
     */
    private fun onFallDetected(impactMagnitude: Float, currentTime: Long) {
        // 쿨다운 체크
        if (currentTime - lastFallNotificationTime < NOTIFICATION_COOLDOWN_MS) return
        lastFallNotificationTime = currentTime

        val message = "센서 기반 낙상 감지: 충격 강도 %.1f m/s² (회전 %.1f rad/s)".format(
            impactMagnitude, currentGyroMagnitude
        )

        // 긴급 알림
        notificationHelper.sendEmergencyNotification(
            title = "⚠ 낙상 감지 (센서)",
            message = message
        )

        // DB 기록
        lifecycleScope.launch {
            repository.saveActivityLog(
                activityType = "FALL_DETECTED",
                confidence = 90,
                isAnomaly = true,
                anomalyDescription = message
            )
        }

        // 브로드캐스트
        sendSensorBroadcast(0f, 0f, 0f, impactMagnitude, true)
    }

    private fun sendSensorBroadcast(x: Float, y: Float, z: Float, magnitude: Float, fallDetected: Boolean) {
        val intent = Intent(ACTION_SENSOR_UPDATE).apply {
            putExtra(EXTRA_ACCEL_X, x)
            putExtra(EXTRA_ACCEL_Y, y)
            putExtra(EXTRA_ACCEL_Z, z)
            putExtra(EXTRA_ACCEL_MAGNITUDE, magnitude)
            putExtra(EXTRA_FALL_DETECTED, fallDetected)
        }
        sendBroadcast(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 정확도 변경 시 로그만 기록
        Log.d(TAG, "센서 정확도 변경: ${sensor?.name} → $accuracy")
    }

    override fun onDestroy() {
        Log.d(TAG, "센서 모니터링 서비스 종료")
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
