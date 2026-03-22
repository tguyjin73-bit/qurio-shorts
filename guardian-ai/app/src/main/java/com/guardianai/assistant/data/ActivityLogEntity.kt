package com.guardianai.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ActivityLogEntity - 사용자 활동 로그를 저장하는 Room 엔티티
 *
 * Activity Recognition API에서 감지된 활동 정보를 데이터베이스에 영속 저장합니다.
 * 각 레코드는 특정 시점에 감지된 활동 유형과 신뢰도를 포함합니다.
 */
@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    /** 자동 증가하는 고유 ID */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 감지된 활동 유형 (STILL, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, TILTING, UNKNOWN) */
    val activityType: String,

    /** 활동 감지 신뢰도 (0~100, 높을수록 정확) */
    val confidence: Int,

    /** 활동이 감지된 시각 (Unix timestamp, 밀리초) */
    val timestamp: Long,

    /** 이상 패턴 감지 여부 (true: 이상 감지됨) */
    val isAnomaly: Boolean = false,

    /** 이상 패턴 설명 (예: "낙상 의심", "장시간 미활동") */
    val anomalyDescription: String? = null
)
