package com.guardianai.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * GuardianDatabase - 가디언 AI 비서의 Room 데이터베이스
 *
 * 싱글톤 패턴으로 구현되어 앱 전체에서 하나의 데이터베이스 인스턴스만 사용합니다.
 * 현재 버전 1에서는 활동 로그 테이블만 포함합니다.
 */
@Database(
    entities = [ActivityLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GuardianDatabase : RoomDatabase() {

    /** 활동 로그 DAO를 반환하는 추상 함수 */
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        /** 데이터베이스 싱글톤 인스턴스 (volatile로 스레드 안전성 보장) */
        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        /**
         * 데이터베이스 인스턴스를 가져오는 함수 (싱글톤 패턴)
         * 이미 인스턴스가 존재하면 기존 인스턴스를 반환하고,
         * 없으면 synchronized 블록 내에서 새로 생성합니다.
         *
         * @param context 애플리케이션 컨텍스트 (메모리 누수 방지를 위해 Application Context 사용)
         * @return GuardianDatabase 싱글톤 인스턴스
         */
        fun getInstance(context: Context): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GuardianDatabase::class.java,
                    "guardian_ai_db"
                )
                    .fallbackToDestructiveMigration()  // 스키마 변경 시 기존 데이터 삭제 후 재생성
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
