package com.guardianai.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.guardianai.assistant.NotificationHelper
import com.guardianai.assistant.data.AiSuggestionEntity
import com.guardianai.assistant.data.GuardianDatabase

/**
 * VoicePhishingDetector - 보이스피싱 감지 AI 엔진
 *
 * 통화 중 실시간 텍스트(STT) 또는 통화 메타데이터를 분석하여
 * 보이스피싱 의심 패턴을 감지하고 경고합니다.
 *
 * 감지 기준:
 * 1. 키워드 매칭: 금융사기에 자주 사용되는 키워드/문장 패턴
 * 2. 발신번호 분석: 미등록 번호, 해외 번호, 비정상 패턴
 * 3. 대화 패턴: 긴급성 강조, 송금 유도, 개인정보 요구 등
 * 4. 행동 패턴: 통화 중 금융 앱 전환, ATM 방문 유도 등
 *
 * 모든 AI 모드에서 항상 활성화됩니다 (보이스피싱은 모드와 무관하게 위험).
 */
class VoicePhishingDetector(
    private val context: Context,
    private val notificationHelper: NotificationHelper
) {

    companion object {
        private const val TAG = "VoicePhishing"
        private const val PREFS_NAME = "guardian_ai_prefs"

        /** 위험도 레벨 */
        const val RISK_LOW = 1
        const val RISK_MEDIUM = 2
        const val RISK_HIGH = 3
        const val RISK_CRITICAL = 4

        /** 보이스피싱 키워드 카테고리별 가중치 */
        private val KEYWORD_WEIGHTS = mapOf(
            // 긴급성 강조 (가중치 2)
            "긴급" to 2, "지금 바로" to 2, "즉시" to 2, "빨리" to 2,
            "시간이 없" to 2, "오늘 안에" to 2, "당장" to 2,

            // 금융 유도 (가중치 3)
            "계좌" to 3, "송금" to 3, "이체" to 3, "입금" to 3,
            "현금" to 3, "ATM" to 3, "인출" to 3, "출금" to 3,
            "대출" to 3, "금리" to 3, "저금리" to 3,
            "안전계좌" to 4, "보호계좌" to 4,

            // 기관 사칭 (가중치 3)
            "검찰" to 3, "경찰" to 3, "금융감독원" to 3, "금감원" to 3,
            "국세청" to 3, "법원" to 3, "수사관" to 3, "수사" to 3,
            "체포영장" to 4, "구속" to 4,

            // 개인정보 요구 (가중치 3)
            "주민번호" to 3, "주민등록번호" to 3, "비밀번호" to 3,
            "카드번호" to 3, "인증번호" to 3, "OTP" to 3,
            "공인인증서" to 3, "보안카드" to 3,

            // 협박/압박 (가중치 4)
            "처벌" to 4, "벌금" to 4, "압수" to 4, "동결" to 4,
            "범죄" to 4, "사기" to 3, "피해자" to 3, "연루" to 4,
            "명의도용" to 4, "자금세탁" to 4,

            // 비밀 유지 요구 (가중치 4 - 매우 위험 신호)
            "아무에게도 말하지" to 4, "비밀" to 3, "다른 사람에게 알리면" to 4,
            "가족에게 알리면" to 4, "혼자" to 2,

            // 앱 설치 유도 (가중치 3)
            "앱 설치" to 3, "원격" to 3, "팀뷰어" to 4, "애니데스크" to 4,
            "화면 공유" to 3
        )

        /** 안전한 발신 번호 패턴 (사용자 등록 번호) */
        private const val KEY_TRUSTED_NUMBERS = "trusted_phone_numbers"
    }

    private val suggestionDao = GuardianDatabase.getInstance(context).aiSuggestionDao()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 통화 중 누적 위험 점수 */
    private var currentCallRiskScore = 0
    private var currentCallKeywordsFound = mutableListOf<String>()
    private var warningIssued = false

    /**
     * 실시간 텍스트 분석 - STT(Speech-to-Text)로 변환된 통화 내용을 분석
     *
     * @param transcript 음성 인식된 텍스트 조각
     * @return 위험도 레벨 (RISK_LOW ~ RISK_CRITICAL)
     */
    fun analyzeTranscript(transcript: String): PhishingAnalysisResult {
        val lowerText = transcript.lowercase()
        var segmentScore = 0
        val foundKeywords = mutableListOf<String>()

        // 키워드 매칭
        for ((keyword, weight) in KEYWORD_WEIGHTS) {
            if (lowerText.contains(keyword)) {
                segmentScore += weight
                foundKeywords.add(keyword)
            }
        }

        // 누적 점수 업데이트
        currentCallRiskScore += segmentScore
        currentCallKeywordsFound.addAll(foundKeywords)

        // 위험도 판정
        val riskLevel = when {
            currentCallRiskScore >= 15 -> RISK_CRITICAL
            currentCallRiskScore >= 10 -> RISK_HIGH
            currentCallRiskScore >= 5 -> RISK_MEDIUM
            currentCallRiskScore >= 2 -> RISK_LOW
            else -> 0
        }

        // 위험도에 따른 경고 발송
        if (riskLevel >= RISK_HIGH && !warningIssued) {
            issuePhishingWarning(riskLevel, currentCallKeywordsFound)
            warningIssued = true
        } else if (riskLevel == RISK_CRITICAL) {
            // CRITICAL은 반복 경고
            issuePhishingWarning(riskLevel, currentCallKeywordsFound)
        }

        return PhishingAnalysisResult(
            riskLevel = riskLevel,
            riskScore = currentCallRiskScore,
            detectedKeywords = foundKeywords,
            totalKeywords = currentCallKeywordsFound.distinct(),
            warningMessage = getWarningMessage(riskLevel)
        )
    }

    /**
     * 발신 번호 분석 - 통화 시작 시 호출
     *
     * @param phoneNumber 발신 번호
     * @return 위험 여부 분석 결과
     */
    fun analyzeCallerNumber(phoneNumber: String): CallerAnalysisResult {
        val isTrusted = isTrustedNumber(phoneNumber)
        var riskFlags = mutableListOf<String>()

        // 해외 번호 감지 (한국 번호가 아닌 경우)
        if (!phoneNumber.startsWith("01") && !phoneNumber.startsWith("+82") &&
            !phoneNumber.startsWith("02") && !phoneNumber.startsWith("0")) {
            riskFlags.add("해외/비정상 번호")
        }

        // 발신번호 표시제한
        if (phoneNumber.isEmpty() || phoneNumber == "unknown" || phoneNumber == "비공개") {
            riskFlags.add("발신번호 비공개")
        }

        // 070 인터넷전화 (보이스피싱에 자주 사용)
        if (phoneNumber.startsWith("070")) {
            riskFlags.add("인터넷전화(070)")
        }

        // 국제전화 (+로 시작하고 +82가 아닌 경우)
        if (phoneNumber.startsWith("+") && !phoneNumber.startsWith("+82")) {
            riskFlags.add("국제전화")
        }

        val riskLevel = when {
            isTrusted -> 0
            riskFlags.size >= 2 -> RISK_HIGH
            riskFlags.isNotEmpty() -> RISK_MEDIUM
            else -> RISK_LOW
        }

        return CallerAnalysisResult(
            phoneNumber = phoneNumber,
            isTrusted = isTrusted,
            riskLevel = riskLevel,
            riskFlags = riskFlags
        )
    }

    /**
     * 통화 시작 시 호출 - 상태 초기화
     */
    fun onCallStarted(phoneNumber: String) {
        currentCallRiskScore = 0
        currentCallKeywordsFound.clear()
        warningIssued = false

        val callerResult = analyzeCallerNumber(phoneNumber)
        if (callerResult.riskLevel >= RISK_MEDIUM) {
            Log.w(TAG, "주의 필요 발신번호: $phoneNumber (${callerResult.riskFlags})")
            notificationHelper.sendEmergencyNotification(
                title = "📞 주의: 발신번호 확인",
                message = "등록되지 않은 번호입니다. ${callerResult.riskFlags.joinToString(", ")}"
            )
        }
    }

    /**
     * 통화 종료 시 호출 - 분석 결과 저장
     */
    suspend fun onCallEnded() {
        if (currentCallRiskScore > 0) {
            val riskLevel = when {
                currentCallRiskScore >= 15 -> RISK_CRITICAL
                currentCallRiskScore >= 10 -> RISK_HIGH
                currentCallRiskScore >= 5 -> RISK_MEDIUM
                else -> RISK_LOW
            }

            suggestionDao.insert(
                AiSuggestionEntity(
                    type = "SAFETY",
                    title = "통화 분석 결과",
                    message = "위험 점수: $currentCallRiskScore, 감지 키워드: ${currentCallKeywordsFound.distinct().joinToString(", ")}",
                    priority = riskLevel
                )
            )
        }

        // 상태 초기화
        currentCallRiskScore = 0
        currentCallKeywordsFound.clear()
        warningIssued = false
    }

    /**
     * 신뢰할 수 있는 번호 등록 (가족, 지인)
     */
    fun addTrustedNumber(phoneNumber: String) {
        val current = getTrustedNumbers().toMutableSet()
        current.add(normalizeNumber(phoneNumber))
        prefs.edit().putStringSet(KEY_TRUSTED_NUMBERS, current).apply()
        Log.d(TAG, "신뢰 번호 등록: $phoneNumber")
    }

    /**
     * 신뢰 번호 목록
     */
    fun getTrustedNumbers(): Set<String> {
        return prefs.getStringSet(KEY_TRUSTED_NUMBERS, emptySet()) ?: emptySet()
    }

    private fun isTrustedNumber(phoneNumber: String): Boolean {
        val normalized = normalizeNumber(phoneNumber)
        return getTrustedNumbers().any { normalized.contains(it) || it.contains(normalized) }
    }

    private fun normalizeNumber(number: String): String {
        return number.replace("-", "").replace(" ", "").replace("+82", "0")
    }

    /**
     * 보이스피싱 경고 발송
     */
    private fun issuePhishingWarning(riskLevel: Int, keywords: List<String>) {
        val title = when (riskLevel) {
            RISK_CRITICAL -> "🚨 보이스피싱 강력 의심!"
            RISK_HIGH -> "⚠️ 보이스피싱 주의!"
            else -> "📞 통화 주의"
        }
        val keywordStr = keywords.distinct().take(5).joinToString(", ")
        val message = when (riskLevel) {
            RISK_CRITICAL -> "이 통화는 보이스피싱일 가능성이 매우 높습니다!\n감지 키워드: $keywordStr\n\n즉시 통화를 끊고 가족이나 경찰(112)에 확인하세요!"
            RISK_HIGH -> "보이스피싱 의심 키워드가 감지되었습니다.\n감지: $keywordStr\n\n주의하시고, 개인정보나 금융정보를 절대 알려주지 마세요."
            else -> "주의 키워드: $keywordStr"
        }

        notificationHelper.sendEmergencyNotification(title = title, message = message)
        Log.w(TAG, "보이스피싱 경고 발송: level=$riskLevel, keywords=$keywordStr")
    }

    private fun getWarningMessage(riskLevel: Int): String? = when (riskLevel) {
        RISK_CRITICAL -> "보이스피싱 강력 의심! 즉시 전화를 끊으세요!"
        RISK_HIGH -> "보이스피싱 주의! 개인정보를 절대 알려주지 마세요."
        RISK_MEDIUM -> "주의가 필요한 통화입니다."
        else -> null
    }
}

/** 텍스트 분석 결과 */
data class PhishingAnalysisResult(
    val riskLevel: Int,
    val riskScore: Int,
    val detectedKeywords: List<String>,
    val totalKeywords: List<String>,
    val warningMessage: String?
)

/** 발신번호 분석 결과 */
data class CallerAnalysisResult(
    val phoneNumber: String,
    val isTrusted: Boolean,
    val riskLevel: Int,
    val riskFlags: List<String>
)
