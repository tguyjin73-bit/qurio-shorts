package com.guardianai.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UserPatternEntity - 사용자의 학습된 행동 패턴을 저장하는 엔티티
 *
 * AI가 사용자의 일상 루틴을 분석하여 시간대별 패턴을 학습합니다.
 * 예: "월~금 오전 7시에 걷기 활동 시작", "매일 오후 10시에 정지 상태(수면)"
 *
 * 이 데이터를 기반으로 비정상적인 행동을 감지하거나
 * 사용자에게 자율적 제안을 제공합니다.
 */
@Entity(tableName = "user_pattern")
data class UserPatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 패턴 카테고리: ACTIVITY, LOCATION, SLEEP, ROUTINE */
    val category: String,

    /** 요일 (1=월 ~ 7=일, 0=매일) */
    val dayOfWeek: Int,

    /** 시간대 (0~23시) */
    val hourOfDay: Int,

    /** 해당 시간대의 주요 활동 유형 */
    val dominantActivity: String,

    /** 이 패턴이 관측된 횟수 (높을수록 확립된 패턴) */
    val occurrenceCount: Int = 1,

    /** 패턴 신뢰도 (0.0 ~ 1.0, 관측 횟수 기반 산출) */
    val confidence: Float = 0.0f,

    /** 패턴에 대한 AI 메모 (예: "매일 아침 산책 루틴") */
    val aiNote: String? = null,

    /** 마지막 업데이트 시각 */
    val lastUpdated: Long = System.currentTimeMillis(),

    /** 패턴이 처음 감지된 시각 */
    val firstDetected: Long = System.currentTimeMillis()
)
