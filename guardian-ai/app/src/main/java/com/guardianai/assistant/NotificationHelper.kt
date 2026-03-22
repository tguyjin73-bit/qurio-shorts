package com.guardianai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * NotificationHelper - 가디언 AI 비서의 알림 관리 헬퍼
 *
 * 포그라운드 서비스 상시 알림과 긴급 상황 알림을 관리합니다.
 * Android 8.0(Oreo) 이상에서 필수인 알림 채널을 자동 생성합니다.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        /** 포그라운드 서비스용 알림 채널 ID */
        const val CHANNEL_MONITORING = "guardian_monitoring"

        /** 긴급 알림용 채널 ID */
        const val CHANNEL_EMERGENCY = "guardian_emergency"

        /** 포그라운드 서비스 알림 ID */
        const val NOTIFICATION_ID_FOREGROUND = 1001

        /** 긴급 알림 ID */
        const val NOTIFICATION_ID_EMERGENCY = 2001
    }

    /** 시스템 알림 관리자 */
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * 초기화 블록 - 객체 생성 시 알림 채널들을 자동 생성
     * Android 8.0(API 26) 이상에서 알림을 표시하려면 반드시 채널이 필요합니다.
     */
    init {
        createNotificationChannels()
    }

    /**
     * 알림 채널들을 생성하는 함수
     * - 모니터링 채널: 포그라운드 서비스 상시 알림 (낮은 중요도)
     * - 긴급 채널: 위험 감지 시 긴급 알림 (높은 중요도, 소리/진동)
     */
    private fun createNotificationChannels() {
        // 모니터링 채널 - 상시 표시되므로 낮은 중요도로 설정하여 소리/진동 없음
        val monitoringChannel = NotificationChannel(
            CHANNEL_MONITORING,
            "활동 모니터링",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "가디언 AI가 사용자 활동을 모니터링 중임을 표시합니다."
            setShowBadge(false)  // 앱 아이콘에 배지 표시하지 않음
        }

        // 긴급 채널 - 위험 감지 시 즉시 알려야 하므로 높은 중요도
        val emergencyChannel = NotificationChannel(
            CHANNEL_EMERGENCY,
            "긴급 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "낙상, 장시간 미활동 등 위험 상황이 감지되었을 때 알림합니다."
            enableVibration(true)                              // 진동 활성화
            vibrationPattern = longArrayOf(0, 500, 200, 500)   // 진동 패턴: 즉시-500ms-200ms쉼-500ms
            setShowBadge(true)                                 // 앱 아이콘에 배지 표시
        }

        // 시스템에 채널 등록
        notificationManager.createNotificationChannel(monitoringChannel)
        notificationManager.createNotificationChannel(emergencyChannel)
    }

    /**
     * 포그라운드 서비스용 상시 알림을 생성하는 함수
     * 이 알림은 서비스가 실행되는 동안 알림바에 계속 표시됩니다.
     *
     * @param currentActivity 현재 감지된 활동 유형 (예: "걷기", "정지")
     * @return 포그라운드 서비스에 사용할 Notification 객체
     */
    fun createForegroundNotification(currentActivity: String = "초기화 중"): Notification {
        // 알림 클릭 시 메인 화면으로 이동하기 위한 PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP  // 기존 액티비티가 있으면 재사용
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setContentTitle("가디언 AI 모니터링 중")
            .setContentText("현재 활동: $currentActivity")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)  // 기본 아이콘 사용
            .setContentIntent(pendingIntent)
            .setOngoing(true)       // 사용자가 스와이프로 제거할 수 없음
            .setSilent(true)        // 소리 없음
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 포그라운드 서비스 알림의 활동 상태 텍스트를 갱신하는 함수
     * 활동 유형이 변경될 때마다 호출하여 알림 내용을 업데이트합니다.
     *
     * @param currentActivity 새로 감지된 활동 유형
     */
    fun updateForegroundNotification(currentActivity: String) {
        val notification = createForegroundNotification(currentActivity)
        notificationManager.notify(NOTIFICATION_ID_FOREGROUND, notification)
    }

    /**
     * 긴급 상황 알림을 발송하는 함수
     * 낙상 의심, 장시간 미활동 등 이상 패턴이 감지되었을 때 호출합니다.
     *
     * @param title 알림 제목 (예: "낙상 의심 감지")
     * @param message 알림 내용 (예: "갑자기 활동이 중단되었습니다. 괜찮으신가요?")
     */
    fun sendEmergencyNotification(title: String, message: String) {
        // 알림 클릭 시 메인 화면으로 이동
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_EMERGENCY)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)  // 경고 아이콘
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)    // 클릭 시 자동으로 알림 제거
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)    // 알람 카테고리
            .setDefaults(NotificationCompat.DEFAULT_ALL)       // 기본 소리, 진동, LED
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(message)  // 긴 텍스트 표시
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID_EMERGENCY, notification)
    }
}
