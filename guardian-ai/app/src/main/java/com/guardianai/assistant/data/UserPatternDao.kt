package com.guardianai.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UserPatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: UserPatternEntity): Long

    @Update
    suspend fun update(pattern: UserPatternEntity)

    /** 특정 요일+시간대의 패턴 조회 */
    @Query("SELECT * FROM user_pattern WHERE dayOfWeek = :dayOfWeek AND hourOfDay = :hour AND category = :category LIMIT 1")
    suspend fun getPattern(dayOfWeek: Int, hour: Int, category: String): UserPatternEntity?

    /** 매일(0) 또는 특정 요일의 패턴 조회 */
    @Query("SELECT * FROM user_pattern WHERE (dayOfWeek = :dayOfWeek OR dayOfWeek = 0) AND hourOfDay = :hour ORDER BY confidence DESC")
    suspend fun getPatternsForTime(dayOfWeek: Int, hour: Int): List<UserPatternEntity>

    /** 특정 카테고리의 모든 패턴 (신뢰도 순) */
    @Query("SELECT * FROM user_pattern WHERE category = :category ORDER BY confidence DESC")
    suspend fun getPatternsByCategory(category: String): List<UserPatternEntity>

    /** 신뢰도가 높은 확립된 패턴 조회 */
    @Query("SELECT * FROM user_pattern WHERE confidence >= :minConfidence ORDER BY confidence DESC")
    suspend fun getEstablishedPatterns(minConfidence: Float = 0.6f): List<UserPatternEntity>

    /** 전체 패턴 수 */
    @Query("SELECT COUNT(*) FROM user_pattern")
    suspend fun getPatternCount(): Int

    /** 오래된 패턴 정리 (90일 이상 미업데이트) */
    @Query("DELETE FROM user_pattern WHERE lastUpdated < :beforeTimestamp AND confidence < 0.5")
    suspend fun deleteStalePatterns(beforeTimestamp: Long): Int
}
