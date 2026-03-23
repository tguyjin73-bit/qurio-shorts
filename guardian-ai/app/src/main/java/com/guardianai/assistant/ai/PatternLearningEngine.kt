package com.guardianai.assistant.ai

import android.content.Context
import android.util.Log
import com.guardianai.assistant.data.ActivityLogEntity
import com.guardianai.assistant.data.GuardianDatabase
import com.guardianai.assistant.data.UserPatternEntity
import java.util.Calendar

/**
 * PatternLearningEngine - 사용자 행동 패턴 학습 AI 엔진
 *
 * 축적된 활동 로그를 분석하여 사용자의 일상 루틴을 자동으로 학습합니다.
 *
 * 학습 과정:
 * 1. 시간대별 활동 빈도 집계 (요일별 × 시간대별 × 활동 유형)
 * 2. 각 시간 슬롯의 지배적 활동(dominant activity) 식별
 * 3. 반복 관측 시 신뢰도 상승 → 패턴 확립
 * 4. 확립된 패턴과 현재 행동 비교 → 이탈 감지
 *
 * 이 엔진은 주기적으로(1시간마다) 호출되어 새 데이터를 학습합니다.
 */
class PatternLearningEngine(context: Context) {

    companion object {
        private const val TAG = "PatternLearning"

        /** 패턴 확립에 필요한 최소 관측 횟수 */
        private const val MIN_OBSERVATIONS_FOR_PATTERN = 3

        /** 최대 신뢰도 */
        private const val MAX_CONFIDENCE = 0.95f

        /** 관측당 신뢰도 증가량 */
        private const val CONFIDENCE_INCREMENT = 0.1f

        /** 분석 대상 기간 (7일) */
        private const val ANALYSIS_WINDOW_DAYS = 7
    }

    private val patternDao = GuardianDatabase.getInstance(context).userPatternDao()
    private val activityDao = GuardianDatabase.getInstance(context).activityLogDao()

    /**
     * 메인 학습 함수 - 최근 활동 로그를 분석하여 패턴을 업데이트
     * 1시간마다 호출됩니다.
     */
    suspend fun learnFromRecentActivity() {
        Log.d(TAG, "패턴 학습 시작...")

        val now = System.currentTimeMillis()
        val windowStart = now - (ANALYSIS_WINDOW_DAYS * 24 * 60 * 60 * 1000L)

        // 최근 7일간의 활동 로그 조회
        val recentLogs = activityDao.getLogsBetween(windowStart, now)
        if (recentLogs.isEmpty()) {
            Log.d(TAG, "학습할 데이터 없음")
            return
        }

        Log.d(TAG, "분석 대상 로그: ${recentLogs.size}건")

        // 요일+시간대별로 활동 그룹핑
        val groupedLogs = groupLogsByTimeSlot(recentLogs)

        // 각 시간 슬롯별 지배적 활동 학습
        for ((key, logs) in groupedLogs) {
            val (dayOfWeek, hourOfDay) = key
            learnTimeSlotPattern(dayOfWeek, hourOfDay, logs)
        }

        // 오래된 패턴 정리
        val staleThreshold = now - (90L * 24 * 60 * 60 * 1000L)
        val deleted = patternDao.deleteStalePatterns(staleThreshold)
        if (deleted > 0) {
            Log.d(TAG, "오래된 패턴 ${deleted}건 정리")
        }

        val totalPatterns = patternDao.getPatternCount()
        Log.d(TAG, "패턴 학습 완료. 총 패턴 수: $totalPatterns")
    }

    /**
     * 활동 로그를 (요일, 시간대) 키로 그룹핑
     */
    private fun groupLogsByTimeSlot(logs: List<ActivityLogEntity>): Map<Pair<Int, Int>, List<ActivityLogEntity>> {
        return logs.groupBy { log ->
            val cal = Calendar.getInstance().apply { timeInMillis = log.timestamp }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=일 ~ 7=토
            val mappedDay = when (dayOfWeek) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                Calendar.SUNDAY -> 7
                else -> 0
            }
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
            Pair(mappedDay, hourOfDay)
        }
    }

    /**
     * 특정 시간 슬롯의 패턴을 학습/업데이트
     */
    private suspend fun learnTimeSlotPattern(
        dayOfWeek: Int,
        hourOfDay: Int,
        logs: List<ActivityLogEntity>
    ) {
        // 가장 빈번한 활동 유형 찾기
        val activityCounts = logs.groupBy { it.activityType }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        val dominantActivity = activityCounts.first().first
        val dominantCount = activityCounts.first().second
        val totalCount = logs.size

        // 기존 패턴 조회
        val existingPattern = patternDao.getPattern(dayOfWeek, hourOfDay, "ACTIVITY")

        if (existingPattern != null) {
            // 기존 패턴 업데이트
            val newCount = existingPattern.occurrenceCount + dominantCount
            val newConfidence = (newCount.toFloat() / (newCount + MIN_OBSERVATIONS_FOR_PATTERN))
                .coerceAtMost(MAX_CONFIDENCE)

            val aiNote = generateAiNote(dayOfWeek, hourOfDay, dominantActivity, newConfidence)

            patternDao.update(
                existingPattern.copy(
                    dominantActivity = dominantActivity,
                    occurrenceCount = newCount,
                    confidence = newConfidence,
                    aiNote = aiNote,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else if (dominantCount >= 2) {
            // 새 패턴 생성 (최소 2회 이상 관측된 경우)
            val initialConfidence = dominantCount.toFloat() / (dominantCount + MIN_OBSERVATIONS_FOR_PATTERN)
            val aiNote = generateAiNote(dayOfWeek, hourOfDay, dominantActivity, initialConfidence)

            patternDao.insert(
                UserPatternEntity(
                    category = "ACTIVITY",
                    dayOfWeek = dayOfWeek,
                    hourOfDay = hourOfDay,
                    dominantActivity = dominantActivity,
                    occurrenceCount = dominantCount,
                    confidence = initialConfidence,
                    aiNote = aiNote
                )
            )
            Log.d(TAG, "새 패턴 발견: ${getDayName(dayOfWeek)} ${hourOfDay}시 → $dominantActivity")
        }
    }

    /**
     * 현재 행동이 학습된 패턴에서 벗어났는지 판단
     *
     * @return 이탈 시 (기대 활동, 신뢰도) 반환, 정상이면 null
     */
    suspend fun detectPatternDeviation(currentActivity: String): PatternDeviation? {
        val cal = Calendar.getInstance()
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 0
        }
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)

        val patterns = patternDao.getPatternsForTime(dayOfWeek, hourOfDay)
        if (patterns.isEmpty()) return null

        val bestPattern = patterns.first()

        // 신뢰도가 충분하고, 현재 활동이 기대와 다른 경우
        if (bestPattern.confidence >= 0.6f && bestPattern.dominantActivity != currentActivity) {
            return PatternDeviation(
                expectedActivity = bestPattern.dominantActivity,
                actualActivity = currentActivity,
                confidence = bestPattern.confidence,
                aiNote = bestPattern.aiNote,
                dayOfWeek = dayOfWeek,
                hourOfDay = hourOfDay
            )
        }

        return null
    }

    /**
     * 사용자의 현재 시간대에 맞는 루틴 예측
     */
    suspend fun predictNextActivity(): String? {
        val cal = Calendar.getInstance()
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 0
        }
        val nextHour = (cal.get(Calendar.HOUR_OF_DAY) + 1) % 24

        val patterns = patternDao.getPatternsForTime(dayOfWeek, nextHour)
        return patterns.firstOrNull()?.let { pattern ->
            if (pattern.confidence >= 0.5f) {
                getActivityDisplayName(pattern.dominantActivity)
            } else null
        }
    }

    /**
     * 확립된 패턴 요약 (사용자에게 보여줄 학습 결과)
     */
    suspend fun getLearnedRoutineSummary(): List<String> {
        val patterns = patternDao.getEstablishedPatterns(0.6f)
        return patterns.map { p ->
            "${getDayName(p.dayOfWeek)} ${p.hourOfDay}시: ${getActivityDisplayName(p.dominantActivity)} " +
                    "(확신도 ${(p.confidence * 100).toInt()}%)"
        }
    }

    /** AI가 패턴에 대해 자연스러운 메모를 생성 */
    private fun generateAiNote(dayOfWeek: Int, hour: Int, activity: String, confidence: Float): String {
        val dayStr = getDayName(dayOfWeek)
        val actStr = getActivityDisplayName(activity)
        val timeDesc = when (hour) {
            in 5..8 -> "아침"
            in 9..11 -> "오전"
            in 12..13 -> "점심"
            in 14..17 -> "오후"
            in 18..20 -> "저녁"
            in 21..23 -> "밤"
            else -> "새벽"
        }

        return when {
            confidence >= 0.8f -> "${dayStr} ${timeDesc}에 항상 ${actStr} 하시네요"
            confidence >= 0.6f -> "${dayStr} ${timeDesc}에 주로 ${actStr} 하시는 편이에요"
            else -> "${dayStr} ${timeDesc}에 ${actStr} 하실 때가 있어요"
        }
    }

    private fun getDayName(day: Int): String = when (day) {
        1 -> "월요일"; 2 -> "화요일"; 3 -> "수요일"; 4 -> "목요일"
        5 -> "금요일"; 6 -> "토요일"; 7 -> "일요일"; else -> "매일"
    }

    private fun getActivityDisplayName(type: String): String = when (type) {
        "STILL" -> "정지(휴식)"
        "WALKING" -> "걷기(산책)"
        "RUNNING" -> "달리기(운동)"
        "IN_VEHICLE" -> "차량 이동"
        "ON_BICYCLE" -> "자전거"
        "ON_FOOT" -> "도보"
        "TILTING" -> "움직임"
        else -> "활동"
    }
}

/** 패턴 이탈 정보 */
data class PatternDeviation(
    val expectedActivity: String,
    val actualActivity: String,
    val confidence: Float,
    val aiNote: String?,
    val dayOfWeek: Int,
    val hourOfDay: Int
)
