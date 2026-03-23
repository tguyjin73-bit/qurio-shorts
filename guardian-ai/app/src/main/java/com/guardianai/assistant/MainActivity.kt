package com.guardianai.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.guardianai.assistant.ai.AiMode
import com.guardianai.assistant.ai.PatternLearningEngine
import com.guardianai.assistant.ai.ProactiveSuggestionManager
import com.guardianai.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private lateinit var patternEngine: PatternLearningEngine
    private lateinit var suggestionManager: ProactiveSuggestionManager

    private var isMonitoring = false
    private var isSensorMonitoring = false
    private var isCameraMonitoring = false
    private var isPhishingMonitoring = false

    companion object {
        private const val PREFS_NAME = "guardian_ai_prefs"
        private const val KEY_CONTACT_119 = "contact_119"
        private const val KEY_CONTACT_FAMILY = "contact_family"
        private const val KEY_CONTACT_FRIEND = "contact_friend"
        private const val KEY_FAMILY_PHONE = "family_phone"
        private const val KEY_FRIEND_PHONE = "friend_phone"
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val KEY_IS_SENSOR_MONITORING = "is_sensor_monitoring"
        private const val KEY_IS_CAMERA_MONITORING = "is_camera_monitoring"
        private const val KEY_IS_PHISHING_MONITORING = "is_phishing_monitoring"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionStatusUI()
        val deniedPermissions = permissions.filter { !it.value }
        if (deniedPermissions.isNotEmpty()) {
            val hasPermanentlyDenied = deniedPermissions.keys.any { permission ->
                permissionManager.isPermissionPermanentlyDenied(permission)
            }
            if (hasPermanentlyDenied) {
                permissionManager.showSettingsDialog()
            }
        }
    }

    // === 브로드캐스트 리시버들 ===

    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == ActivityRecognitionService.ACTION_ACTIVITY_UPDATE) {
                val activityType = intent.getStringExtra(ActivityRecognitionService.EXTRA_ACTIVITY_TYPE) ?: "UNKNOWN"
                val confidence = intent.getIntExtra(ActivityRecognitionService.EXTRA_CONFIDENCE, 0)
                updateCurrentActivityUI(activityType, confidence)
            }
        }
    }

    private val sensorUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == SensorMonitorService.ACTION_SENSOR_UPDATE) {
                val magnitude = intent.getFloatExtra(SensorMonitorService.EXTRA_ACCEL_MAGNITUDE, 0f)
                val fallDetected = intent.getBooleanExtra(SensorMonitorService.EXTRA_FALL_DETECTED, false)
                updateSensorUI(magnitude, fallDetected)
            }
        }
    }

    private val cameraUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == CameraMonitorService.ACTION_CAMERA_UPDATE) {
                val status = intent.getStringExtra(CameraMonitorService.EXTRA_CAMERA_STATUS) ?: "알 수 없음"
                updateCameraUI(status)
            }
        }
    }

    private val callStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == CallMonitorService.ACTION_CALL_STATUS) {
                val state = intent.getStringExtra(CallMonitorService.EXTRA_CALL_STATE) ?: ""
                val riskLevel = intent.getIntExtra(CallMonitorService.EXTRA_RISK_LEVEL, 0)
                updatePhishingUI(state, riskLevel)
            }
        }
    }

    // === 라이프사이클 ===

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)
        patternEngine = PatternLearningEngine(this)
        suggestionManager = ProactiveSuggestionManager(
            this, patternEngine, NotificationHelper(this)
        )

        // 이전 상태 복원
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isMonitoring = prefs.getBoolean(KEY_IS_MONITORING, false)
        isSensorMonitoring = prefs.getBoolean(KEY_IS_SENSOR_MONITORING, false)
        isCameraMonitoring = prefs.getBoolean(KEY_IS_CAMERA_MONITORING, false)
        isPhishingMonitoring = prefs.getBoolean(KEY_IS_PHISHING_MONITORING, false)

        setupToolbar()
        setupAiModeToggle()
        setupPhishingButton()
        setupPermissionButton()
        setupMonitoringButton()
        setupSensorButton()
        setupCameraButton()
        setupEmergencyContacts()
        loadSavedContacts()

        updatePermissionStatusUI()
        updateMonitoringUI()
        updateSensorMonitoringUI()
        updateCameraMonitoringUI()
        updatePhishingMonitoringUI()

        // 학습된 패턴 표시
        loadLearnedPatterns()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusUI()

        val filters = listOf(
            ActivityRecognitionService.ACTION_ACTIVITY_UPDATE to activityUpdateReceiver,
            SensorMonitorService.ACTION_SENSOR_UPDATE to sensorUpdateReceiver,
            CameraMonitorService.ACTION_CAMERA_UPDATE to cameraUpdateReceiver,
            CallMonitorService.ACTION_CALL_STATUS to callStatusReceiver
        )

        for ((action, receiver) in filters) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(activityUpdateReceiver)
            unregisterReceiver(sensorUpdateReceiver)
            unregisterReceiver(cameraUpdateReceiver)
            unregisterReceiver(callStatusReceiver)
        } catch (e: IllegalArgumentException) { }
    }

    // === Setup 함수들 ===

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    /** AI 모드 토글 설정 */
    private fun setupAiModeToggle() {
        // 현재 모드 반영
        val currentMode = suggestionManager.currentMode
        when (currentMode) {
            AiMode.ACTIVE -> binding.toggleAiMode.check(binding.btnModeActive.id)
            AiMode.NORMAL -> binding.toggleAiMode.check(binding.btnModeNormal.id)
            AiMode.QUIET -> binding.toggleAiMode.check(binding.btnModeQuiet.id)
        }
        binding.tvAiModeDescription.text = currentMode.description

        binding.toggleAiMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.btnModeActive.id -> AiMode.ACTIVE
                binding.btnModeNormal.id -> AiMode.NORMAL
                binding.btnModeQuiet.id -> AiMode.QUIET
                else -> AiMode.NORMAL
            }
            suggestionManager.currentMode = mode
            binding.tvAiModeDescription.text = mode.description
            Toast.makeText(this, "AI 모드: ${mode.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 보이스피싱 감시 버튼 */
    private fun setupPhishingButton() {
        binding.btnTogglePhishing.setOnClickListener {
            if (isPhishingMonitoring) {
                CallMonitorService.stop(this)
                isPhishingMonitoring = false
                saveState()
                updatePhishingMonitoringUI()
                Toast.makeText(this, "보이스피싱 감시가 중지되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                if (!permissionManager.isPermissionGranted(Manifest.permission.READ_PHONE_STATE) ||
                    !permissionManager.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, "통화 감지 및 음성 인식 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                    permissionManager.requestAllPermissions(permissionLauncher)
                    return@setOnClickListener
                }
                CallMonitorService.start(this)
                isPhishingMonitoring = true
                saveState()
                updatePhishingMonitoringUI()
                Toast.makeText(this, "보이스피싱 감시가 시작되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPermissionButton() {
        binding.btnRequestPermissions.setOnClickListener {
            if (permissionManager.areAllPermissionsGranted()) {
                Toast.makeText(this, "모든 권한이 이미 허용되었습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            permissionManager.requestAllPermissions(permissionLauncher)
        }
    }

    private fun setupMonitoringButton() {
        binding.btnToggleMonitoring.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                if (!permissionManager.areAllPermissionsGranted()) {
                    Toast.makeText(this, "모니터링을 시작하려면 모든 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
                    permissionManager.requestAllPermissions(permissionLauncher)
                    return@setOnClickListener
                }
                startMonitoring()
            }
        }
    }

    private fun setupSensorButton() {
        binding.btnToggleSensor.setOnClickListener {
            if (isSensorMonitoring) {
                SensorMonitorService.stop(this)
                isSensorMonitoring = false
                saveState()
                updateSensorMonitoringUI()
                Toast.makeText(this, "센서 모니터링이 중지되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                if (!permissionManager.areAllPermissionsGranted()) {
                    Toast.makeText(this, "권한을 먼저 허용해 주세요.", Toast.LENGTH_LONG).show()
                    permissionManager.requestAllPermissions(permissionLauncher)
                    return@setOnClickListener
                }
                SensorMonitorService.start(this)
                isSensorMonitoring = true
                saveState()
                updateSensorMonitoringUI()
                Toast.makeText(this, "센서 모니터링이 시작되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupCameraButton() {
        binding.btnToggleCamera.setOnClickListener {
            if (isCameraMonitoring) {
                CameraMonitorService.stop(this)
                isCameraMonitoring = false
                saveState()
                updateCameraMonitoringUI()
                Toast.makeText(this, "카메라 모니터링이 중지되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                if (!permissionManager.isPermissionGranted(Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "카메라 권한을 먼저 허용해 주세요.", Toast.LENGTH_LONG).show()
                    permissionManager.requestAllPermissions(permissionLauncher)
                    return@setOnClickListener
                }
                CameraMonitorService.start(this)
                isCameraMonitoring = true
                saveState()
                updateCameraMonitoringUI()
                Toast.makeText(this, "카메라 모니터링이 시작되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMonitoring() {
        ActivityRecognitionService.start(this)
        isMonitoring = true
        saveState()
        updateMonitoringUI()
        Toast.makeText(this, "활동 모니터링이 시작되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        ActivityRecognitionService.stop(this)
        isMonitoring = false
        saveState()
        updateMonitoringUI()
        Toast.makeText(this, "활동 모니터링이 중지되었습니다.", Toast.LENGTH_SHORT).show()
    }

    // === 상태 저장/UI 업데이트 ===

    private fun saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_IS_MONITORING, isMonitoring)
            putBoolean(KEY_IS_SENSOR_MONITORING, isSensorMonitoring)
            putBoolean(KEY_IS_CAMERA_MONITORING, isCameraMonitoring)
            putBoolean(KEY_IS_PHISHING_MONITORING, isPhishingMonitoring)
            apply()
        }
    }

    private fun updateMonitoringUI() {
        if (isMonitoring) {
            binding.btnToggleMonitoring.text = getString(R.string.stop_monitoring)
            binding.btnToggleMonitoring.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvCurrentActivity.text = "감지 중..."
            binding.tvCurrentActivity.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            binding.btnToggleMonitoring.text = getString(R.string.start_monitoring)
            binding.btnToggleMonitoring.setIconResource(android.R.drawable.ic_media_play)
            binding.tvCurrentActivity.text = getString(R.string.monitoring_stopped)
            binding.tvCurrentActivity.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateSensorMonitoringUI() {
        if (isSensorMonitoring) {
            binding.btnToggleSensor.text = getString(R.string.stop_sensor_monitoring)
            binding.btnToggleSensor.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvAccelStatus.text = "감지 중..."
            binding.tvAccelStatus.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            binding.btnToggleSensor.text = getString(R.string.start_sensor_monitoring)
            binding.btnToggleSensor.setIconResource(android.R.drawable.ic_menu_compass)
            binding.tvAccelStatus.text = getString(R.string.sensor_status_off)
            binding.tvAccelStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateCameraMonitoringUI() {
        if (isCameraMonitoring) {
            binding.btnToggleCamera.text = getString(R.string.stop_camera_monitoring)
            binding.btnToggleCamera.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvCameraStatus.text = "활성"
            binding.tvCameraStatus.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            binding.btnToggleCamera.text = getString(R.string.start_camera_monitoring)
            binding.btnToggleCamera.setIconResource(android.R.drawable.ic_menu_camera)
            binding.tvCameraStatus.text = getString(R.string.camera_status_off)
            binding.tvCameraStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updatePhishingMonitoringUI() {
        if (isPhishingMonitoring) {
            binding.btnTogglePhishing.text = getString(R.string.stop_phishing_monitor)
            binding.btnTogglePhishing.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvPhishingStatus.text = "감시 중"
            binding.tvPhishingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            binding.btnTogglePhishing.text = getString(R.string.start_phishing_monitor)
            binding.btnTogglePhishing.setIconResource(android.R.drawable.ic_menu_call)
            binding.tvPhishingStatus.text = getString(R.string.phishing_status_off)
            binding.tvPhishingStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateCurrentActivityUI(activityType: String, confidence: Int) {
        val displayName = when (activityType) {
            "STILL" -> "정지"; "WALKING" -> "걷기"; "RUNNING" -> "달리기"
            "IN_VEHICLE" -> "차량 탑승"; "ON_BICYCLE" -> "자전거"
            "ON_FOOT" -> "도보"; "TILTING" -> "기울임"; else -> "알 수 없음"
        }
        binding.tvCurrentActivity.text = "$displayName ($confidence%)"
        binding.tvCurrentActivity.setTextColor(ContextCompat.getColor(this, R.color.primary))
    }

    private fun updateSensorUI(magnitude: Float, fallDetected: Boolean) {
        if (fallDetected) {
            binding.tvAccelStatus.text = "⚠ 낙상 감지!"
            binding.tvAccelStatus.setTextColor(ContextCompat.getColor(this, R.color.status_denied))
        } else {
            binding.tvAccelStatus.text = "%.1f m/s²".format(magnitude)
            binding.tvAccelStatus.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        }
    }

    private fun updateCameraUI(status: String) {
        binding.tvCameraStatus.text = status
        val color = when (status) {
            "활성" -> R.color.status_granted
            "이상 감지" -> R.color.status_denied
            else -> R.color.text_secondary
        }
        binding.tvCameraStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun updatePhishingUI(state: String, riskLevel: Int) {
        val statusText = when {
            riskLevel >= 3 -> "🚨 위험 감지!"
            riskLevel >= 2 -> "⚠ 주의"
            state == "IN_CALL" -> "통화 감시 중"
            state == "RINGING" -> "전화 수신 중"
            else -> "감시 중"
        }
        binding.tvPhishingStatus.text = statusText
        val color = when {
            riskLevel >= 3 -> R.color.status_denied
            riskLevel >= 2 -> R.color.accent
            else -> R.color.status_granted
        }
        binding.tvPhishingStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    /** 학습된 패턴 표시 */
    private fun loadLearnedPatterns() {
        lifecycleScope.launch {
            val patterns = patternEngine.getLearnedRoutineSummary()
            if (patterns.isNotEmpty()) {
                binding.tvLearnedPatterns.text = "📊 학습된 루틴:\n" + patterns.take(5).joinToString("\n") { "• $it" }
            } else {
                binding.tvLearnedPatterns.text = getString(R.string.ai_no_patterns)
            }
        }
    }

    // === 권한 상태 ===

    private fun updatePermissionStatusUI() {
        updateSinglePermissionStatus(binding.tvLocationStatus, permissionManager.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION))
        updateSinglePermissionStatus(binding.tvActivityStatus, permissionManager.isPermissionGranted(Manifest.permission.ACTIVITY_RECOGNITION))
        updateSinglePermissionStatus(binding.tvSmsStatus, permissionManager.isPermissionGranted(Manifest.permission.SEND_SMS))
        updateSinglePermissionStatus(binding.tvPhoneStatus, permissionManager.isPermissionGranted(Manifest.permission.CALL_PHONE))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.layoutNotificationPermission.visibility = View.VISIBLE
            updateSinglePermissionStatus(binding.tvNotificationStatus, permissionManager.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            binding.layoutNotificationPermission.visibility = View.GONE
        }

        updateSinglePermissionStatus(binding.tvCameraPermissionStatus, permissionManager.isPermissionGranted(Manifest.permission.CAMERA))

        if (permissionManager.areAllPermissionsGranted()) {
            binding.btnRequestPermissions.isEnabled = false
            binding.btnRequestPermissions.text = "모든 권한 허용됨"
        } else {
            binding.btnRequestPermissions.isEnabled = true
            binding.btnRequestPermissions.text = "권한 요청하기"
        }
    }

    private fun updateSinglePermissionStatus(textView: android.widget.TextView, isGranted: Boolean) {
        if (isGranted) {
            textView.text = getString(R.string.status_granted)
            textView.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            textView.text = getString(R.string.status_denied)
            textView.setTextColor(ContextCompat.getColor(this, R.color.status_denied))
        }
    }

    // === 긴급 연락처 ===

    private fun setupEmergencyContacts() {
        binding.cbFamily.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFamily.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.cbFriend.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFriend.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnSaveContacts.setOnClickListener { saveEmergencyContacts() }
    }

    private fun saveEmergencyContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_CONTACT_119, binding.cb119.isChecked)
            putBoolean(KEY_CONTACT_FAMILY, binding.cbFamily.isChecked)
            putBoolean(KEY_CONTACT_FRIEND, binding.cbFriend.isChecked)
            if (binding.cbFamily.isChecked) putString(KEY_FAMILY_PHONE, binding.etFamilyPhone.text.toString().trim())
            if (binding.cbFriend.isChecked) putString(KEY_FRIEND_PHONE, binding.etFriendPhone.text.toString().trim())
            apply()
        }
        Toast.makeText(this, getString(R.string.contacts_saved), Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.cb119.isChecked = prefs.getBoolean(KEY_CONTACT_119, false)
        val isFamilyChecked = prefs.getBoolean(KEY_CONTACT_FAMILY, false)
        binding.cbFamily.isChecked = isFamilyChecked
        if (isFamilyChecked) {
            binding.tilFamily.visibility = View.VISIBLE
            binding.etFamilyPhone.setText(prefs.getString(KEY_FAMILY_PHONE, ""))
        }
        val isFriendChecked = prefs.getBoolean(KEY_CONTACT_FRIEND, false)
        binding.cbFriend.isChecked = isFriendChecked
        if (isFriendChecked) {
            binding.tilFriend.visibility = View.VISIBLE
            binding.etFriendPhone.setText(prefs.getString(KEY_FRIEND_PHONE, ""))
        }
    }
}
