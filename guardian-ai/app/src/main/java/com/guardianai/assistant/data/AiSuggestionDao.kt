package com.guardianai.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AiSuggestionDao {

    @Insert
    suspend fun insert(suggestion: AiSuggestionEntity): Long

    @Update
    suspend fun update(suggestion: AiSuggestionEntity)

    /** 읽지 않은 제안 조회 (우선순위 높은 순) */
    @Query("SELECT * FROM ai_suggestion WHERE isRead = 0 ORDER BY priority DESC, createdAt DESC")
    suspend fun getUnreadSuggestions(): List<AiSuggestionEntity>

    /** 최근 N개 제안 조회 */
    @Query("SELECT * FROM ai_suggestion ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentSuggestions(limit: Int = 20): List<AiSuggestionEntity>

    /** 사용자가 수락한 제안 조회 (AI 학습용) */
    @Query("SELECT * FROM ai_suggestion WHERE userFeedback = 'ACCEPTED' ORDER BY createdAt DESC")
    suspend fun getAcceptedSuggestions(): List<AiSuggestionEntity>

    /** 사용자가 거절한 제안 조회 (AI 학습용) */
    @Query("SELECT * FROM ai_suggestion WHERE userFeedback = 'DISMISSED' ORDER BY createdAt DESC")
    suspend fun getDismissedSuggestions(): List<AiSuggestionEntity>

    /** 특정 유형의 최근 제안 (중복 제안 방지용) */
    @Query("SELECT * FROM ai_suggestion WHERE type = :type AND createdAt > :sinceTimestamp ORDER BY createdAt DESC LIMIT 1")
    suspend fun getRecentSuggestionByType(type: String, sinceTimestamp: Long): AiSuggestionEntity?

    /** 제안 읽음 처리 */
    @Query("UPDATE ai_suggestion SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    /** 사용자 피드백 기록 */
    @Query("UPDATE ai_suggestion SET userFeedback = :feedback WHERE id = :id")
    suspend fun setFeedback(id: Long, feedback: String)

    /** 오래된 제안 삭제 (30일) */
    @Query("DELETE FROM ai_suggestion WHERE createdAt < :beforeTimestamp")
    suspend fun deleteOldSuggestions(beforeTimestamp: Long): Int
}
