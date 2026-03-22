package com.guardianai.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.guardianai.assistant.data.ActivityLogRepository
import kotlinx.coroutines.launch

/**
 * ActivityRecognitionService - 활동 감지 포그라운드 서비스
 *
 * Google Play Services의 Activity Recognition API를 사용하여
 * 사용자의 활동(걷기, 달리기, 정지 등)을 실시간으로 감지합니다.
 * 포그라운드 서비스로 동작하여 앱이 백그라운드에 있어도 지속적으로 모니터링합니다.
 */
class ActivityRecognitionService : LifecycleService() {

    companion object {
        private const val TAG = "GuardianActivityService"

        /** 활동 감지 업데이트 주기 (10초마다) */
        private const val DETECTION_INTERVAL_MS = 10_000L

        /** 활동 감지 브로드캐스트 액션 */
        const val ACTION_ACTIVITY_DETECTED = "com.guardianai.ACTION_ACTIVITY_DETECTED"

        /** 현재 감지된 활동을 외부에 브로드캐스트하는 액션 */
        const val ACTION_ACTIVITY_UPDATE = "com.guardianai.ACTION_ACTIVITY_UPDATE"

        /** 인텐트 엑스트라 키: 활동 유형 */
        const val EXTRA_ACTIVITY_TYPE = "activity_type"

        /** 인텐트 엑스트라 키: 활동 신뢰도 */
        const val EXTRA_CONFIDENCE = "confidence"

        /**
         * 서비스 시작을 위한 헬퍼 함수
         * 다른 컴포넌트에서 이 서비스를 쉽게 시작할 수 있도록 합니다.
         *
         * @param context 컨텍스트
         */
        fun start(context: Context) {
            val intent = Intent(context, ActivityRecognitionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 서비스 중지를 위한 헬퍼 함수
         *
         * @param context 컨텍스트
         */
        fun stop(context: Context) {
            val intent = Intent(context, ActivityRecognitionService::class.java)
            context.stopService(intent)
        }
    }

    /** Activity Recognition API 클라이언트 */
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    /** 알림 헬퍼 */
    private lateinit var notificationHelper: NotificationHelper

    /** 활동 로그 레포지토리 */
    private lateinit var repository: ActivityLogRepository

    /** 이상 패턴 감지기 */
    private lateinit var anomalyDetector: AnomalyDetector

    /** Activity Recognition 결과를 수신하는 PendingIntent */
    private var activityPendingIntent: PendingIntent? = null

    /**
     * 활동 감지 결과를 수신하는 BroadcastReceiver
     * Activity Recognition API가 활동을 감지할 때마다 호출됩니다.
     */
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return

            // ActivityRecognitionResult에서 감지 결과 추출
            if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                handleActivityResult(result)
            }
        }
    }

    /**
     * 서비스가 생성될 때 호출되는 콜백
     * 필요한 컴포넌트들을 초기화합니다.
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "서비스 생성됨")

        // 컴포넌트 초기화
        activityRecognitionClient = ActivityRecognition.getClient(this)
        notificationHelper = NotificationHelper(this)
        repository = ActivityLogRepository(this)
        anomalyDetector = AnomalyDetector(repository, notificationHelper)

        // 브로드캐스트 리시버 등록
        val filter = IntentFilter(ACTION_ACTIVITY_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(activityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(activityReceiver, filter)
        }
    }

    /**
     * 서비스 시작 시 호출되는 콜백
     * 포그라운드 서비스를 시작하고 활동 감지를 등록합니다.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "서비스 시작됨")

        // 포그라운드 서비스 시작 - 알림 표시
        startForeground(
            NotificationHelper.NOTIFICATION_ID_FOREGROUND,
            notificationHelper.createForegroundNotification()
        )

        // Activity Recognition 등록
        requestActivityUpdates()

        // 서비스가 시스템에 의해 종료된 경우 자동 재시작
        return START_STICKY
    }

    /**
     * Activity Recognition API에 활동 감지 업데이트를 요청하는 함수
     * 지정된 주기마다 사용자의 활동 상태를 감지하여 브로드캐스트로 전달합니다.
     */
    private fun requestActivityUpdates() {
        // 활동 감지 결과를 수신할 PendingIntent 생성
        val intent = Intent(ACTION_ACTIVITY_DETECTED)
        activityPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Activity Recognition API에 업데이트 요청
        try {
            activityRecognitionClient.requestActivityUpdates(
                DETECTION_INTERVAL_MS,
                activityPendingIntent!!
            ).addOnSuccessListener {
                Log.d(TAG, "활동 감지 등록 성공 (주기: ${DETECTION_INTERVAL_MS}ms)")
            }.addOnFailureListener { e ->
                Log.e(TAG, "활동 감지 등록 실패: ${e.message}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "활동 감지 권한 없음: ${e.message}")
        }
    }

    /**
     * Activity Recognition 결과를 처리하는 함수
     * 가장 신뢰도가 높은 활동을 선택하여 이상 패턴 분석 및 UI 업데이트를 수행합니다.
     *
     * @param result ActivityRecognitionResult 감지 결과
     */
    private fun handleActivityResult(result: ActivityRecognitionResult) {
        // 가장 신뢰도가 높은 활동을 선택
        val mostProbableActivity = result.mostProbableActivity
        val activityType = AnomalyDetector.fromDetectedActivityType(mostProbableActivity.type)
        val confidence = mostProbableActivity.confidence
        val activityName = getActivityDisplayName(activityType)

        Log.d(TAG, "활동 감지: $activityName (신뢰도: $confidence%)")

        // 이상 패턴 분석 (DB 저장도 이 안에서 처리)
        anomalyDetector.analyzeActivity(activityType, confidence, lifecycleScope)

        // 포그라운드 알림 업데이트 (현재 활동 표시)
        notificationHelper.updateForegroundNotification("$activityName ($confidence%)")

        // 메인 화면에 현재 활동 상태를 브로드캐스트
        sendActivityUpdateBroadcast(activityType, confidence)
    }

    /**
     * 현재 감지된 활동 정보를 브로드캐스트하는 함수
     * MainActivity에서 이 브로드캐스트를 수신하여 UI를 업데이트합니다.
     *
     * @param activityType 활동 유형 문자열
     * @param confidence 신뢰도
     */
    private fun sendActivityUpdateBroadcast(activityType: String, confidence: Int) {
        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            putExtra(EXTRA_ACTIVITY_TYPE, activityType)
            putExtra(EXTRA_CONFIDENCE, confidence)
        }
        sendBroadcast(intent)
    }

    /**
     * 활동 유형 코드를 사용자 표시용 한글 이름으로 변환하는 함수
     *
     * @param activityType 활동 유형 코드
     * @return 표시용 한글 이름
     */
    private fun getActivityDisplayName(activityType: String): String {
        return when (activityType) {
            "STILL" -> "정지"
            "WALKING" -> "걷기"
            "RUNNING" -> "달리기"
            "IN_VEHICLE" -> "차량 탑승"
            "ON_BICYCLE" -> "자전거"
            "ON_FOOT" -> "도보"
            "TILTING" -> "기울임"
            else -> "알 수 없음"
        }
    }

    /**
     * 서비스가 소멸될 때 호출되는 콜백
     * 활동 감지 등록을 해제하고 리소스를 정리합니다.
     */
    override fun onDestroy() {
        Log.d(TAG, "서비스 종료됨")

        // Activity Recognition 업데이트 해제
        activityPendingIntent?.let { pendingIntent ->
            try {
                activityRecognitionClient.removeActivityUpdates(pendingIntent)
                    .addOnSuccessListener {
                        Log.d(TAG, "활동 감지 해제 성공")
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "활동 감지 해제 실패: ${e.message}")
            }
        }

        // 브로드캐스트 리시버 해제
        try {
            unregisterReceiver(activityReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "리시버가 이미 해제되었습니다")
        }

        // 오래된 로그 정리 (7일 이전 데이터 삭제)
        lifecycleScope.launch {
            val deletedCount = repository.cleanOldLogs()
            Log.d(TAG, "오래된 로그 ${deletedCount}건 정리 완료")
        }

        super.onDestroy()
    }

    /**
     * 바인딩은 사용하지 않으므로 null 반환
     */
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
