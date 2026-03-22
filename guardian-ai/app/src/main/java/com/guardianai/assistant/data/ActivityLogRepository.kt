package com.guardianai.assistant.data

import android.content.Context

/**
 * ActivityLogRepository - 활동 로그 데이터 접근을 위한 레포지토리
 *
 * DAO를 감싸서 데이터 접근 로직을 캡슐화합니다.
 * 서비스나 뷰모델에서 직접 DAO에 접근하지 않고 이 레포지토리를 통해 데이터를 조작합니다.
 */
class ActivityLogRepository(context: Context) {

    /** 데이터베이스 DAO 인스턴스 */
    private val dao = GuardianDatabase.getInstance(context).activityLogDao()

    /**
     * 새로운 활동 로그를 저장하는 함수
     *
     * @param activityType 활동 유형 문자열
     * @param confidence 감지 신뢰도 (0~100)
     * @param isAnomaly 이상 패턴 여부
     * @param anomalyDescription 이상 패턴 설명 (선택)
     * @return 저장된 로그의 ID
     */
    suspend fun saveActivityLog(
        activityType: String,
        confidence: Int,
        isAnomaly: Boolean = false,
        anomalyDescription: String? = null
    ): Long {
        val log = ActivityLogEntity(
            activityType = activityType,
            confidence = confidence,
            timestamp = System.currentTimeMillis(),
            isAnomaly = isAnomaly,
            anomalyDescription = anomalyDescription
        )
        return dao.insert(log)
    }

    /**
     * 최근 활동 로그를 조회하는 함수
     *
     * @param limit 최대 조회 개수 (기본 50개)
     * @return 최근 활동 로그 목록
     */
    suspend fun getRecentLogs(limit: Int = 50): List<ActivityLogEntity> {
        return dao.getRecentLogs(limit)
    }

    /**
     * 가장 최근 활동 로그 1건을 조회하는 함수
     *
     * @return 최근 활동 로그 (없으면 null)
     */
    suspend fun getLatestLog(): ActivityLogEntity? {
        return dao.getLatestLog()
    }

    /**
     * 특정 시간 범위의 활동 로그를 조회하는 함수
     *
     * @param startTime 시작 시각 (밀리초)
     * @param endTime 종료 시각 (밀리초)
     * @return 해당 범위의 활동 로그 목록
     */
    suspend fun getLogsBetween(startTime: Long, endTime: Long): List<ActivityLogEntity> {
        return dao.getLogsBetween(startTime, endTime)
    }

    /**
     * 이상 패턴 로그만 조회하는 함수
     *
     * @return 이상 패턴 활동 로그 목록
     */
    suspend fun getAnomalyLogs(): List<ActivityLogEntity> {
        return dao.getAnomalyLogs()
    }

    /**
     * 오래된 로그를 정리하는 함수
     * 기본적으로 7일 이전의 로그를 삭제합니다.
     *
     * @param retentionDays 보존 기간 (일 단위, 기본 7일)
     * @return 삭제된 로그 수
     */
    suspend fun cleanOldLogs(retentionDays: Int = 7): Int {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        return dao.deleteOldLogs(cutoffTime)
    }
}
