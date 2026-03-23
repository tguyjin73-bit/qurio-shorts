package com.guardianai.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AiSuggestionEntity - AI가 생성한 자율 제안을 저장하는 엔티티
 *
 * 학습된 패턴을 기반으로 AI가 사용자에게 제안하는 내용을 기록합니다.
 * 사용자의 피드백(수락/거절)도 저장하여 향후 제안 품질을 개선합니다.
 */
@Entity(tableName = "ai_suggestion")
data class AiSuggestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 제안 유형: HEALTH, SAFETY, ROUTINE, WEATHER, SOCIAL, REMINDER */
    val type: String,

    /** 제안 제목 */
    val title: String,

    /** 제안 내용 */
    val message: String,

    /** 제안 우선순위 (1=낮음, 2=보통, 3=높음, 4=긴급) */
    val priority: Int = 2,

    /** 제안이 생성된 시각 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 사용자가 확인했는지 여부 */
    val isRead: Boolean = false,

    /** 사용자 피드백: ACCEPTED, DISMISSED, IGNORED, null(아직 미응답) */
    val userFeedback: String? = null,

    /** 이 제안을 트리거한 패턴 ID (user_pattern 테이블 참조) */
    val triggerPatternId: Long? = null,

    /** 이 제안을 생성한 AI 모드 (ACTIVE, NORMAL, QUIET) */
    val aiMode: String = "NORMAL"
)
