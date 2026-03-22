package com.guardianai.assistant

import com.guardianai.assistant.data.ActivityLogEntity
import com.guardianai.assistant.data.ActivityLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AnomalyDetector - 사용자 활동 이상 패턴 감지기
 *
 * Activity Recognition API에서 수신한 활동 데이터를 분석하여
 * 낙상 의심, 장시간 미활동 등의 이상 패턴을 감지합니다.
 * 이상이 감지되면 NotificationHelper를 통해 긴급 알림을 발송합니다.
 */
class AnomalyDetector(
    private val repository: ActivityLogRepository,
    private val notificationHelper: NotificationHelper
) {

    companion object {
        /** 장시간 미활동 판단 임계값 (기본 30분, 밀리초 단위) */
        const val INACTIVITY_THRESHOLD_MS = 30 * 60 * 1000L

        /** 급격한 활동 변화 감지를 위한 시간 윈도우 (5초) */
        const val SUDDEN_CHANGE_WINDOW_MS = 5 * 1000L

        /** 이상 감지 최소 신뢰도 (이 값 이상일 때만 이상으로 판단) */
        const val MIN_CONFIDENCE_THRESHOLD = 50
    }

    /** 이전에 감지된 활동 유형 (급격한 변화 감지용) */
    private var previousActivity: String? = null

    /** 이전 활동이 감지된 시각 */
    private var previousActivityTimestamp: Long = 0L

    /** 마지막 이상 알림 발송 시각 (알림 폭탄 방지용) */
    private var lastAnomalyNotificationTime: Long = 0L

    /** 알림 쿨다운 시간 (동일 이상 패턴에 대해 3분 내 재알림 방지) */
    private val notificationCooldownMs = 3 * 60 * 1000L

    /**
     * 새로 감지된 활동을 분석하여 이상 패턴을 판별하는 메인 함수
     * ActivityRecognitionService에서 활동이 감지될 때마다 호출됩니다.
     *
     * @param activityType 감지된 활동 유형 문자열
     * @param confidence 감지 신뢰도 (0~100)
     * @param scope 비동기 DB 작업을 실행할 코루틴 스코프
     */
    fun analyzeActivity(activityType: String, confidence: Int, scope: CoroutineScope) {
        val currentTime = System.currentTimeMillis()

        // 신뢰도가 임계값 미만이면 분석하지 않음 (오탐 방지)
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return
        }

        // 1단계: 급격한 활동 변화 감지 (낙상 의심)
        val suddenChangeAnomaly = detectSuddenChange(activityType, currentTime)

        // 2단계: 장시간 미활동 감지
        val inactivityAnomaly = detectProlongedInactivity(activityType, currentTime)

        // 이상 패턴이 감지된 경우
        val isAnomaly = suddenChangeAnomaly != null || inactivityAnomaly != null
        val anomalyDescription = suddenChangeAnomaly ?: inactivityAnomaly

        // DB에 활동 로그 저장 (이상 여부 포함)
        scope.launch(Dispatchers.IO) {
            repository.saveActivityLog(
                activityType = activityType,
                confidence = confidence,
                isAnomaly = isAnomaly,
                anomalyDescription = anomalyDescription
            )
        }

        // 이상 감지 시 알림 발송 (쿨다운 확인)
        if (isAnomaly && canSendNotification(currentTime)) {
            sendAnomalyNotification(anomalyDescription!!)
            lastAnomalyNotificationTime = currentTime
        }

        // 현재 활동을 다음 분석을 위해 저장
        previousActivity = activityType
        previousActivityTimestamp = currentTime
    }

    /**
     * 급격한 활동 변화를 감지하는 함수 (낙상 의심)
     * 짧은 시간 내에 활발한 활동(WALKING, RUNNING)에서
     * 갑자기 정지(STILL) 상태로 전환되면 낙상 의심으로 판단합니다.
     *
     * @param currentActivity 현재 감지된 활동 유형
     * @param currentTime 현재 시각 (밀리초)
     * @return 이상 감지 시 설명 문자열, 정상이면 null
     */
    private fun detectSuddenChange(currentActivity: String, currentTime: Long): String? {
        val prev = previousActivity ?: return null

        // 이전 활동으로부터 경과 시간 계산
        val timeDiff = currentTime - previousActivityTimestamp

        // 짧은 시간 내 활발한 활동 → 정지 전환 감지
        if (timeDiff <= SUDDEN_CHANGE_WINDOW_MS) {
            val wasActive = prev in listOf("WALKING", "RUNNING", "ON_BICYCLE")
            val isNowStill = currentActivity == "STILL"

            if (wasActive && isNowStill) {
                return "낙상 의심: ${getActivityName(prev)}에서 갑자기 정지 상태로 전환됨"
            }
        }

        return null
    }

    /**
     * 장시간 미활동을 감지하는 함수
     * STILL(정지) 상태가 설정된 임계 시간 이상 지속되면 이상으로 판단합니다.
     *
     * @param currentActivity 현재 감지된 활동 유형
     * @param currentTime 현재 시각 (밀리초)
     * @return 이상 감지 시 설명 문자열, 정상이면 null
     */
    private fun detectProlongedInactivity(currentActivity: String, currentTime: Long): String? {
        if (previousActivity == null) return null

        // 이전 활동도 STILL이고, 현재도 STILL인 경우 경과 시간 확인
        if (previousActivity == "STILL" && currentActivity == "STILL") {
            val duration = currentTime - previousActivityTimestamp
            if (duration >= INACTIVITY_THRESHOLD_MS) {
                val minutes = duration / (60 * 1000)
                return "장시간 미활동: ${minutes}분 동안 움직임이 감지되지 않음"
            }
        }

        return null
    }

    /**
     * 알림 발송 가능 여부를 확인하는 함수 (쿨다운 체크)
     * 동일한 이상 패턴에 대해 짧은 시간 내에 반복 알림을 방지합니다.
     *
     * @param currentTime 현재 시각 (밀리초)
     * @return 알림 발송 가능하면 true
     */
    private fun canSendNotification(currentTime: Long): Boolean {
        return currentTime - lastAnomalyNotificationTime > notificationCooldownMs
    }

    /**
     * 이상 패턴 감지 알림을 발송하는 함수
     *
     * @param description 이상 패턴 설명 문자열
     */
    private fun sendAnomalyNotification(description: String) {
        notificationHelper.sendEmergencyNotification(
            title = "⚠ 이상 감지",
            message = description
        )
    }

    /**
     * 활동 유형 코드를 한글 이름으로 변환하는 함수
     *
     * @param activityType 활동 유형 코드 (예: "WALKING")
     * @return 한글 활동 이름 (예: "걷기")
     */
    private fun getActivityName(activityType: String): String {
        return when (activityType) {
            "STILL" -> "정지"
            "WALKING" -> "걷기"
            "RUNNING" -> "달리기"
            "IN_VEHICLE" -> "차량 탑승"
            "ON_BICYCLE" -> "자전거"
            "TILTING" -> "기울임"
            else -> "알 수 없음"
        }
    }

    /**
     * 활동 유형 코드를 한글 이름으로 변환하는 정적 함수
     * 외부에서 활동 이름이 필요할 때 사용합니다.
     *
     * @param typeInt DetectedActivity 타입 정수 값
     * @return 활동 유형 문자열 코드
     */
    companion object ActivityTypeConverter {
        /**
         * Google DetectedActivity 정수 타입을 문자열로 변환하는 함수
         *
         * @param type DetectedActivity.getType() 반환값
         * @return 활동 유형 문자열 (예: "WALKING", "STILL")
         */
        fun fromDetectedActivityType(type: Int): String {
            return when (type) {
                0 -> "IN_VEHICLE"
                1 -> "ON_BICYCLE"
                2 -> "ON_FOOT"
                3 -> "STILL"
                5 -> "TILTING"
                7 -> "WALKING"
                8 -> "RUNNING"
                else -> "UNKNOWN"
            }
        }
    }
}
