package com.guardianai.assistant.ai

/**
 * AiMode - AI 비서의 적극성 모드
 *
 * 사용자가 원하는 수준으로 AI의 개입 정도를 조절합니다.
 */
enum class AiMode(val displayName: String, val description: String) {
    /** 적극 모드 - 사소한 변화도 알려주고, 자주 제안 */
    ACTIVE("적극", "작은 변화도 알려드리고 자주 제안해요"),

    /** 보통 모드 - 중요한 변화와 유용한 제안만 */
    NORMAL("보통", "중요한 것만 알려드려요"),

    /** 조용 모드 - 긴급 상황에만 알림 */
    QUIET("조용", "긴급한 상황에만 알려드려요");

    /** 이 모드에서 제안을 보내기 위한 최소 우선순위 */
    val minPriority: Int get() = when (this) {
        ACTIVE -> 1   // 모든 제안
        NORMAL -> 2   // 보통 이상
        QUIET -> 4    // 긴급만
    }

    /** 패턴 이탈 알림 여부 */
    val notifyPatternDeviation: Boolean get() = this != QUIET

    /** 루틴 제안 여부 */
    val suggestRoutine: Boolean get() = this == ACTIVE

    /** 보이스피싱 경고는 모든 모드에서 항상 활성 */
    val alwaysAlertVoicePhishing: Boolean get() = true
}
