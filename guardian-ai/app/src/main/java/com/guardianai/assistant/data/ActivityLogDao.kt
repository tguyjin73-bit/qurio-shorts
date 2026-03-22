package com.guardianai.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * ActivityLogDao - 활동 로그 데이터베이스 접근 객체
 *
 * Room Database에 저장된 활동 로그를 CRUD 조작하기 위한 DAO 인터페이스입니다.
 * 모든 쿼리 메서드는 suspend 함수로 정의되어 코루틴에서 비동기적으로 실행됩니다.
 */
@Dao
interface ActivityLogDao {

    /**
     * 새로운 활동 로그를 데이터베이스에 삽입하는 함수
     *
     * @param log 삽입할 ActivityLogEntity 객체
     * @return 삽입된 행의 ID
     */
    @Insert
    suspend fun insert(log: ActivityLogEntity): Long

    /**
     * 최근 N개의 활동 로그를 시간 역순으로 조회하는 함수
     * 메인 화면에서 최근 활동 이력을 표시할 때 사용합니다.
     *
     * @param limit 조회할 최대 레코드 수
     * @return 최근 활동 로그 목록
     */
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 50): List<ActivityLogEntity>

    /**
     * 특정 시간 범위 내의 활동 로그를 조회하는 함수
     * 이상 패턴 분석 시 최근 일정 시간 내의 활동을 분석하기 위해 사용합니다.
     *
     * @param startTime 조회 시작 시각 (Unix timestamp, 밀리초)
     * @param endTime 조회 종료 시각 (Unix timestamp, 밀리초)
     * @return 해당 시간 범위의 활동 로그 목록
     */
    @Query("SELECT * FROM activity_log WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getLogsBetween(startTime: Long, endTime: Long): List<ActivityLogEntity>

    /**
     * 이상 패턴으로 표시된 로그만 조회하는 함수
     * 이상 감지 이력을 사용자에게 보여줄 때 사용합니다.
     *
     * @return 이상 패턴이 감지된 활동 로그 목록
     */
    @Query("SELECT * FROM activity_log WHERE isAnomaly = 1 ORDER BY timestamp DESC")
    suspend fun getAnomalyLogs(): List<ActivityLogEntity>

    /**
     * 가장 최근에 기록된 활동 로그 1건을 조회하는 함수
     * 현재 사용자 활동 상태를 확인할 때 사용합니다.
     *
     * @return 가장 최근 활동 로그 (없으면 null)
     */
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLog(): ActivityLogEntity?

    /**
     * 지정된 시각 이전의 오래된 로그를 일괄 삭제하는 함수
     * 저장 공간 관리를 위해 오래된 데이터를 정리할 때 사용합니다.
     *
     * @param beforeTimestamp 이 시각 이전의 로그를 삭제 (Unix timestamp, 밀리초)
     * @return 삭제된 행의 수
     */
    @Query("DELETE FROM activity_log WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldLogs(beforeTimestamp: Long): Int
}
