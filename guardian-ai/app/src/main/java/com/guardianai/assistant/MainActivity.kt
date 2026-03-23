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
import com.guardianai.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager

    private var isMonitoring = false
    private var isSensorMonitoring = false
    private var isCameraMonitoring = false

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

    /** 활동 감지 브로드캐스트 리시버 */
    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == ActivityRecognitionService.ACTION_ACTIVITY_UPDATE) {
                val activityType = intent.getStringExtra(
                    ActivityRecognitionService.EXTRA_ACTIVITY_TYPE
                ) ?: "UNKNOWN"
                val confidence = intent.getIntExtra(
                    ActivityRecognitionService.EXTRA_CONFIDENCE, 0
                )
                updateCurrentActivityUI(activityType, confidence)
            }
        }
    }

    /** 센서 데이터 브로드캐스트 리시버 */
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

    /** 카메라 상태 브로드캐스트 리시버 */
    private val cameraUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == CameraMonitorService.ACTION_CAMERA_UPDATE) {
                val status = intent.getStringExtra(CameraMonitorService.EXTRA_CAMERA_STATUS) ?: "알 수 없음"
                updateCameraUI(status)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)

        // 이전 상태 복원
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isMonitoring = prefs.getBoolean(KEY_IS_MONITORING, false)
        isSensorMonitoring = prefs.getBoolean(KEY_IS_SENSOR_MONITORING, false)
        isCameraMonitoring = prefs.getBoolean(KEY_IS_CAMERA_MONITORING, false)

        setupToolbar()
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
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusUI()

        // 활동 업데이트 리시버 등록
        val activityFilter = IntentFilter(ActivityRecognitionService.ACTION_ACTIVITY_UPDATE)
        val sensorFilter = IntentFilter(SensorMonitorService.ACTION_SENSOR_UPDATE)
        val cameraFilter = IntentFilter(CameraMonitorService.ACTION_CAMERA_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(activityUpdateReceiver, activityFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(sensorUpdateReceiver, sensorFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(cameraUpdateReceiver, cameraFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(activityUpdateReceiver, activityFilter)
            registerReceiver(sensorUpdateReceiver, sensorFilter)
            registerReceiver(cameraUpdateReceiver, cameraFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(activityUpdateReceiver)
            unregisterReceiver(sensorUpdateReceiver)
            unregisterReceiver(cameraUpdateReceiver)
        } catch (e: IllegalArgumentException) {
            // 리시버가 이미 해제된 경우 무시
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
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

    /** 센서 모니터링 버튼 설정 */
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

    /** 카메라 모니터링 버튼 설정 */
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

    private fun saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(KEY_IS_MONITORING, isMonitoring)
            putBoolean(KEY_IS_SENSOR_MONITORING, isSensorMonitoring)
            putBoolean(KEY_IS_CAMERA_MONITORING, isCameraMonitoring)
            apply()
        }
    }

    private fun updateMonitoringUI() {
        if (isMonitoring) {
            binding.btnToggleMonitoring.text = getString(R.string.stop_monitoring)
            binding.btnToggleMonitoring.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvCurrentActivity.text = "감지 중..."
            binding.tvCurrentActivity.setTextColor(
                ContextCompat.getColor(this, R.color.status_granted)
            )
        } else {
            binding.btnToggleMonitoring.text = getString(R.string.start_monitoring)
            binding.btnToggleMonitoring.setIconResource(android.R.drawable.ic_media_play)
            binding.tvCurrentActivity.text = getString(R.string.monitoring_stopped)
            binding.tvCurrentActivity.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun updateSensorMonitoringUI() {
        if (isSensorMonitoring) {
            binding.btnToggleSensor.text = getString(R.string.stop_sensor_monitoring)
            binding.btnToggleSensor.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvAccelStatus.text = "감지 중..."
            binding.tvAccelStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_granted)
            )
        } else {
            binding.btnToggleSensor.text = getString(R.string.start_sensor_monitoring)
            binding.btnToggleSensor.setIconResource(android.R.drawable.ic_menu_compass)
            binding.tvAccelStatus.text = getString(R.string.sensor_status_off)
            binding.tvAccelStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun updateCameraMonitoringUI() {
        if (isCameraMonitoring) {
            binding.btnToggleCamera.text = getString(R.string.stop_camera_monitoring)
            binding.btnToggleCamera.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvCameraStatus.text = "활성"
            binding.tvCameraStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_granted)
            )
        } else {
            binding.btnToggleCamera.text = getString(R.string.start_camera_monitoring)
            binding.btnToggleCamera.setIconResource(android.R.drawable.ic_menu_camera)
            binding.tvCameraStatus.text = getString(R.string.camera_status_off)
            binding.tvCameraStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun updateCurrentActivityUI(activityType: String, confidence: Int) {
        val displayName = when (activityType) {
            "STILL" -> "정지"
            "WALKING" -> "걷기"
            "RUNNING" -> "달리기"
            "IN_VEHICLE" -> "차량 탑승"
            "ON_BICYCLE" -> "자전거"
            "ON_FOOT" -> "도보"
            "TILTING" -> "기울임"
            else -> "알 수 없음"
        }
        binding.tvCurrentActivity.text = "$displayName ($confidence%)"
        binding.tvCurrentActivity.setTextColor(
            ContextCompat.getColor(this, R.color.primary)
        )
    }

    private fun updateSensorUI(magnitude: Float, fallDetected: Boolean) {
        if (fallDetected) {
            binding.tvAccelStatus.text = "⚠ 낙상 감지!"
            binding.tvAccelStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_denied)
            )
        } else {
            binding.tvAccelStatus.text = "%.1f m/s²".format(magnitude)
            binding.tvAccelStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_granted)
            )
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

    private fun setupEmergencyContacts() {
        binding.cbFamily.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFamily.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.cbFriend.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFriend.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnSaveContacts.setOnClickListener {
            saveEmergencyContacts()
        }
    }

    private fun updatePermissionStatusUI() {
        updateSinglePermissionStatus(
            binding.tvLocationStatus,
            permissionManager.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        )
        updateSinglePermissionStatus(
            binding.tvActivityStatus,
            permissionManager.isPermissionGranted(Manifest.permission.ACTIVITY_RECOGNITION)
        )
        updateSinglePermissionStatus(
            binding.tvSmsStatus,
            permissionManager.isPermissionGranted(Manifest.permission.SEND_SMS)
        )
        updateSinglePermissionStatus(
            binding.tvPhoneStatus,
            permissionManager.isPermissionGranted(Manifest.permission.CALL_PHONE)
        )

        // 알림 권한 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.layoutNotificationPermission.visibility = View.VISIBLE
            updateSinglePermissionStatus(
                binding.tvNotificationStatus,
                permissionManager.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
            )
        } else {
            binding.layoutNotificationPermission.visibility = View.GONE
        }

        // 카메라 권한
        updateSinglePermissionStatus(
            binding.tvCameraPermissionStatus,
            permissionManager.isPermissionGranted(Manifest.permission.CAMERA)
        )

        if (permissionManager.areAllPermissionsGranted()) {
            binding.btnRequestPermissions.isEnabled = false
            binding.btnRequestPermissions.text = "모든 권한 허용됨"
        } else {
            binding.btnRequestPermissions.isEnabled = true
            binding.btnRequestPermissions.text = "권한 요청하기"
        }
    }

    private fun updateSinglePermissionStatus(
        textView: android.widget.TextView,
        isGranted: Boolean
    ) {
        if (isGranted) {
            textView.text = getString(R.string.status_granted)
            textView.setTextColor(ContextCompat.getColor(this, R.color.status_granted))
        } else {
            textView.text = getString(R.string.status_denied)
            textView.setTextColor(ContextCompat.getColor(this, R.color.status_denied))
        }
    }

    private fun saveEmergencyContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_CONTACT_119, binding.cb119.isChecked)
            putBoolean(KEY_CONTACT_FAMILY, binding.cbFamily.isChecked)
            putBoolean(KEY_CONTACT_FRIEND, binding.cbFriend.isChecked)
            if (binding.cbFamily.isChecked) {
                putString(KEY_FAMILY_PHONE, binding.etFamilyPhone.text.toString().trim())
            }
            if (binding.cbFriend.isChecked) {
                putString(KEY_FRIEND_PHONE, binding.etFriendPhone.text.toString().trim())
            }
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
