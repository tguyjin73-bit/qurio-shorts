package com.guardianai.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.guardianai.assistant.NotificationHelper
import com.guardianai.assistant.data.AiSuggestionEntity
import com.guardianai.assistant.data.GuardianDatabase
import java.util.Calendar

/**
 * ProactiveSuggestionManager - AI 자율 제안 관리자
 *
 * 학습된 패턴과 현재 상황을 분석하여 사용자에게 능동적으로 제안합니다.
 *
 * 제안 유형:
 * - HEALTH: 건강 관련 (운동 권유, 수면 알림 등)
 * - SAFETY: 안전 관련 (이상 행동 감지, 보이스피싱 경고)
 * - ROUTINE: 일상 루틴 (습관 알림, 일정 제안)
 * - SOCIAL: 소통 관련 (가족 연락 권유)
 *
 * 사용자 피드백을 반영하여 제안 품질을 계속 개선합니다.
 */
class ProactiveSuggestionManager(
    private val context: Context,
    private val patternEngine: PatternLearningEngine,
    private val notificationHelper: NotificationHelper
) {

    companion object {
        private const val TAG = "ProactiveSuggestion"
        private const val PREFS_NAME = "guardian_ai_prefs"
        private const val KEY_AI_MODE = "ai_mode"

        /** 같은 유형의 제안 재발송 방지 간격 (2시간) */
        private const val SUGGESTION_COOLDOWN_MS = 2 * 60 * 60 * 1000L
    }

    private val suggestionDao = GuardianDatabase.getInstance(context).aiSuggestionDao()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 현재 AI 모드 */
    var currentMode: AiMode
        get() {
            val modeName = prefs.getString(KEY_AI_MODE, AiMode.NORMAL.name) ?: AiMode.NORMAL.name
            return try { AiMode.valueOf(modeName) } catch (e: Exception) { AiMode.NORMAL }
        }
        set(value) {
            prefs.edit().putString(KEY_AI_MODE, value.name).apply()
            Log.d(TAG, "AI 모드 변경: ${value.displayName}")
        }

    /**
     * 주기적 체크 - 현재 상황에 맞는 제안 생성
     * ActivityRecognitionService에서 활동이 감지될 때마다 호출됩니다.
     */
    suspend fun checkAndSuggest(currentActivity: String) {
        val mode = currentMode

        // 1. 패턴 이탈 감지
        if (mode.notifyPatternDeviation) {
            checkPatternDeviation(currentActivity, mode)
        }

        // 2. 시간대 기반 루틴 제안
        if (mode.suggestRoutine) {
            checkRoutineSuggestion(mode)
        }

        // 3. 건강 제안 (장시간 미활동 등)
        checkHealthSuggestion(currentActivity, mode)
    }

    /**
     * 패턴 이탈 감지 → 제안
     */
    private suspend fun checkPatternDeviation(currentActivity: String, mode: AiMode) {
        val deviation = patternEngine.detectPatternDeviation(currentActivity) ?: return

        // 중복 제안 방지
        val recentSuggestion = suggestionDao.getRecentSuggestionByType(
            "ROUTINE", System.currentTimeMillis() - SUGGESTION_COOLDOWN_MS
        )
        if (recentSuggestion != null) return

        val expectedName = getActivityName(deviation.expectedActivity)
        val actualName = getActivityName(deviation.actualActivity)
        val message = "평소 이 시간에는 ${expectedName}을(를) 하시는데, 지금은 ${actualName} 중이시네요. 괜찮으신가요?"

        createSuggestion(
            type = "ROUTINE",
            title = "평소와 다른 활동 감지",
            message = message,
            priority = 2,
            mode = mode,
            patternId = null
        )
    }

    /**
     * 시간대 기반 루틴 제안
     */
    private suspend fun checkRoutineSuggestion(mode: AiMode) {
        val predictedActivity = patternEngine.predictNextActivity() ?: return

        val recentSuggestion = suggestionDao.getRecentSuggestionByType(
            "ROUTINE", System.currentTimeMillis() - SUGGESTION_COOLDOWN_MS
        )
        if (recentSuggestion != null) return

        val cal = Calendar.getInstance()
        val nextHour = (cal.get(Calendar.HOUR_OF_DAY) + 1) % 24
        val message = "${nextHour}시쯤 ${predictedActivity} 하실 시간이에요."

        createSuggestion(
            type = "ROUTINE",
            title = "일정 알림",
            message = message,
            priority = 1,
            mode = mode
        )
    }

    /**
     * 건강 관련 제안 (장시간 미활동 경고 등)
     */
    private suspend fun checkHealthSuggestion(currentActivity: String, mode: AiMode) {
        if (currentActivity != "STILL") return

        val recentSuggestion = suggestionDao.getRecentSuggestionByType(
            "HEALTH", System.currentTimeMillis() - SUGGESTION_COOLDOWN_MS
        )
        if (recentSuggestion != null) return

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // 낮 시간대에 오래 정지해 있으면 운동 권유
        if (hour in 9..17) {
            createSuggestion(
                type = "HEALTH",
                title = "활동 권유",
                message = "오래 앉아 계신 것 같아요. 가볍게 스트레칭이나 산책은 어떠세요?",
                priority = if (mode == AiMode.ACTIVE) 2 else 1,
                mode = mode
            )
        }
    }

    /**
     * 제안 생성 및 알림 발송
     */
    private suspend fun createSuggestion(
        type: String,
        title: String,
        message: String,
        priority: Int,
        mode: AiMode,
        patternId: Long? = null
    ) {
        // 현재 모드의 최소 우선순위보다 낮으면 무시
        if (priority < mode.minPriority) {
            Log.d(TAG, "제안 스킵 (모드: ${mode.displayName}, 우선순위: $priority < ${mode.minPriority})")
            return
        }

        // DB 저장
        val suggestion = AiSuggestionEntity(
            type = type,
            title = title,
            message = message,
            priority = priority,
            aiMode = mode.name,
            triggerPatternId = patternId
        )
        val id = suggestionDao.insert(suggestion)
        Log.d(TAG, "제안 생성: [$type] $title (id=$id)")

        // 알림 발송
        notificationHelper.sendEmergencyNotification(
            title = "🤖 $title",
            message = message
        )
    }

    /**
     * 사용자가 제안에 피드백을 줄 때 호출
     */
    suspend fun onUserFeedback(suggestionId: Long, feedback: String) {
        suggestionDao.setFeedback(suggestionId, feedback)
        Log.d(TAG, "사용자 피드백: suggestionId=$suggestionId, feedback=$feedback")
    }

    /**
     * 읽지 않은 제안 수
     */
    suspend fun getUnreadCount(): Int {
        return suggestionDao.getUnreadSuggestions().size
    }

    private fun getActivityName(type: String): String = when (type) {
        "STILL" -> "휴식"
        "WALKING" -> "걷기"
        "RUNNING" -> "달리기"
        "IN_VEHICLE" -> "차량 이동"
        "ON_BICYCLE" -> "자전거"
        else -> "활동"
    }
}
