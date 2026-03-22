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

/**
 * MainActivity - 가디언 AI 비서의 메인 화면
 *
 * 이 화면은 세 가지 핵심 기능을 제공합니다:
 * 1. 권한 승인 상태를 시각적으로 표시 (허용/거부 상태를 색상으로 구분)
 * 2. 활동 모니터링 서비스 시작/중지 및 현재 활동 상태 표시
 * 3. 긴급 연락처(119, 가족, 지인)를 다중 선택하여 SharedPreferences에 저장
 */
class MainActivity : AppCompatActivity() {

    /** ViewBinding 인스턴스 - 레이아웃의 모든 뷰에 타입 안전하게 접근 */
    private lateinit var binding: ActivityMainBinding

    /** 권한 관리자 인스턴스 */
    private lateinit var permissionManager: PermissionManager

    /** 모니터링 서비스 실행 상태 추적 */
    private var isMonitoring = false

    /** SharedPreferences 키 상수 정의 */
    companion object {
        private const val PREFS_NAME = "guardian_ai_prefs"          // SharedPreferences 파일명
        private const val KEY_CONTACT_119 = "contact_119"           // 119 선택 여부
        private const val KEY_CONTACT_FAMILY = "contact_family"     // 가족 선택 여부
        private const val KEY_CONTACT_FRIEND = "contact_friend"     // 지인 선택 여부
        private const val KEY_FAMILY_PHONE = "family_phone"         // 가족 전화번호
        private const val KEY_FRIEND_PHONE = "friend_phone"         // 지인 전화번호
        private const val KEY_IS_MONITORING = "is_monitoring"       // 모니터링 상태
    }

    /**
     * 다중 권한 요청 결과를 처리하는 ActivityResultLauncher
     * registerForActivityResult를 사용하여 권한 요청 결과를 비동기적으로 수신합니다.
     * 결과를 받으면 UI의 권한 상태 표시를 즉시 갱신합니다.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 권한 요청 결과를 받으면 UI 상태를 갱신
        updatePermissionStatusUI()

        // 거부된 권한이 있는지 확인
        val deniedPermissions = permissions.filter { !it.value }
        if (deniedPermissions.isNotEmpty()) {
            // "다시 묻지 않기"를 선택한 권한이 있는지 확인
            val hasPermanentlyDenied = deniedPermissions.keys.any { permission ->
                permissionManager.isPermissionPermanentlyDenied(permission)
            }

            if (hasPermanentlyDenied) {
                // "다시 묻지 않기" 선택 시 설정 화면으로 안내
                permissionManager.showSettingsDialog()
            }
        }
    }

    /**
     * 활동 감지 서비스로부터 현재 활동 상태를 수신하는 BroadcastReceiver
     * 서비스에서 활동이 감지될 때마다 UI를 업데이트합니다.
     */
    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == ActivityRecognitionService.ACTION_ACTIVITY_UPDATE) {
                // 활동 유형과 신뢰도를 추출하여 UI 업데이트
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

    /**
     * 액티비티 생성 시 호출되는 라이프사이클 콜백
     * ViewBinding 초기화, 권한 관리자 초기화, UI 설정을 수행합니다.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding 초기화 - XML 레이아웃을 인플레이트하고 바인딩 객체 생성
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 권한 관리자 초기화
        permissionManager = PermissionManager(this)

        // 이전 모니터링 상태 복원
        isMonitoring = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_IS_MONITORING, false)

        // UI 초기 설정
        setupToolbar()
        setupPermissionButton()
        setupMonitoringButton()
        setupEmergencyContacts()
        loadSavedContacts()

        // 현재 권한 상태를 UI에 반영
        updatePermissionStatusUI()
        updateMonitoringUI()
    }

    /**
     * 액티비티가 다시 화면에 표시될 때 호출되는 라이프사이클 콜백
     * 설정 화면에서 돌아왔을 때 권한 상태가 변경되었을 수 있으므로 UI를 갱신합니다.
     * 또한 활동 업데이트 브로드캐스트 리시버를 등록합니다.
     */
    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아온 경우 권한 상태가 변경되었을 수 있음
        updatePermissionStatusUI()

        // 활동 업데이트 브로드캐스트 수신 등록
        val filter = IntentFilter(ActivityRecognitionService.ACTION_ACTIVITY_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(activityUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(activityUpdateReceiver, filter)
        }
    }

    /**
     * 액티비티가 화면에서 사라질 때 호출되는 라이프사이클 콜백
     * 브로드캐스트 리시버를 해제하여 메모리 누수를 방지합니다.
     */
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(activityUpdateReceiver)
        } catch (e: IllegalArgumentException) {
            // 리시버가 이미 해제된 경우 무시
        }
    }

    /**
     * 상단 툴바를 설정하는 함수
     * Material 툴바를 액션바로 등록합니다.
     */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    /**
     * 권한 요청 버튼의 클릭 리스너를 설정하는 함수
     * 버튼 클릭 시 PermissionManager를 통해 모든 필수 권한을 요청합니다.
     */
    private fun setupPermissionButton() {
        binding.btnRequestPermissions.setOnClickListener {
            // 모든 권한이 이미 허용된 경우 토스트 메시지 표시
            if (permissionManager.areAllPermissionsGranted()) {
                Toast.makeText(this, "모든 권한이 이미 허용되었습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 거부된 권한들을 일괄 요청
            permissionManager.requestAllPermissions(permissionLauncher)
        }
    }

    /**
     * 모니터링 시작/중지 버튼의 클릭 리스너를 설정하는 함수
     * 권한이 모두 허용된 경우에만 모니터링을 시작할 수 있습니다.
     */
    private fun setupMonitoringButton() {
        binding.btnToggleMonitoring.setOnClickListener {
            if (isMonitoring) {
                // 모니터링 중지
                stopMonitoring()
            } else {
                // 모니터링 시작 전 권한 확인
                if (!permissionManager.areAllPermissionsGranted()) {
                    Toast.makeText(this, "모니터링을 시작하려면 모든 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
                    permissionManager.requestAllPermissions(permissionLauncher)
                    return@setOnClickListener
                }
                startMonitoring()
            }
        }
    }

    /**
     * 활동 모니터링 서비스를 시작하는 함수
     * 포그라운드 서비스를 실행하고 UI 상태를 업데이트합니다.
     */
    private fun startMonitoring() {
        ActivityRecognitionService.start(this)
        isMonitoring = true
        saveMonitoringState()
        updateMonitoringUI()
        Toast.makeText(this, "활동 모니터링이 시작되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * 활동 모니터링 서비스를 중지하는 함수
     */
    private fun stopMonitoring() {
        ActivityRecognitionService.stop(this)
        isMonitoring = false
        saveMonitoringState()
        updateMonitoringUI()
        Toast.makeText(this, "활동 모니터링이 중지되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * 모니터링 상태를 SharedPreferences에 저장하는 함수
     * 앱 재시작 시 이전 모니터링 상태를 복원하기 위해 사용합니다.
     */
    private fun saveMonitoringState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_MONITORING, isMonitoring)
            .apply()
    }

    /**
     * 모니터링 UI 상태를 업데이트하는 함수
     * 모니터링 상태에 따라 버튼 텍스트와 활동 표시를 변경합니다.
     */
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

    /**
     * 현재 감지된 활동 상태를 UI에 표시하는 함수
     * ActivityRecognitionService에서 브로드캐스트로 전달받은 활동 정보를 화면에 반영합니다.
     *
     * @param activityType 활동 유형 코드 (예: "WALKING")
     * @param confidence 감지 신뢰도 (0~100)
     */
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

    /**
     * 긴급 연락처 UI의 이벤트 리스너를 설정하는 함수
     * 체크박스 상태 변경 시 전화번호 입력 필드의 표시/숨김을 제어하고,
     * 저장 버튼 클릭 시 SharedPreferences에 데이터를 저장합니다.
     */
    private fun setupEmergencyContacts() {
        // 가족 체크박스 토글 시 전화번호 입력 필드 표시/숨김
        binding.cbFamily.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFamily.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 지인 체크박스 토글 시 전화번호 입력 필드 표시/숨김
        binding.cbFriend.setOnCheckedChangeListener { _, isChecked ->
            binding.tilFriend.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 저장 버튼 클릭 시 SharedPreferences에 긴급 연락처 정보 저장
        binding.btnSaveContacts.setOnClickListener {
            saveEmergencyContacts()
        }
    }

    /**
     * 각 권한의 허용/거부 상태를 UI에 시각적으로 반영하는 함수
     * 허용된 권한은 녹색, 거부된 권한은 빨간색으로 표시합니다.
     */
    private fun updatePermissionStatusUI() {
        // 위치 권한 상태 업데이트
        updateSinglePermissionStatus(
            binding.tvLocationStatus,
            permissionManager.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        )

        // 활동 감지 권한 상태 업데이트
        updateSinglePermissionStatus(
            binding.tvActivityStatus,
            permissionManager.isPermissionGranted(Manifest.permission.ACTIVITY_RECOGNITION)
        )

        // SMS 전송 권한 상태 업데이트
        updateSinglePermissionStatus(
            binding.tvSmsStatus,
            permissionManager.isPermissionGranted(Manifest.permission.SEND_SMS)
        )

        // 전화 권한 상태 업데이트
        updateSinglePermissionStatus(
            binding.tvPhoneStatus,
            permissionManager.isPermissionGranted(Manifest.permission.CALL_PHONE)
        )

        // 알림 권한 상태 업데이트 (Android 13 이상에서만 표시)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.layoutNotificationPermission.visibility = View.VISIBLE
            updateSinglePermissionStatus(
                binding.tvNotificationStatus,
                permissionManager.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
            )
        } else {
            // Android 13 미만에서는 알림 권한 행을 숨김 (별도 권한 불필요)
            binding.layoutNotificationPermission.visibility = View.GONE
        }

        // 모든 권한이 허용되었으면 버튼 비활성화
        if (permissionManager.areAllPermissionsGranted()) {
            binding.btnRequestPermissions.isEnabled = false
            binding.btnRequestPermissions.text = "모든 권한 허용됨"
        } else {
            binding.btnRequestPermissions.isEnabled = true
            binding.btnRequestPermissions.text = "권한 요청하기"
        }
    }

    /**
     * 개별 권한 상태 텍스트뷰를 업데이트하는 헬퍼 함수
     * 허용 상태에 따라 텍스트와 색상을 변경합니다.
     *
     * @param textView 상태를 표시할 TextView
     * @param isGranted 권한 허용 여부
     */
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

    /**
     * 긴급 연락처 정보를 SharedPreferences에 저장하는 함수
     * 체크박스 선택 상태와 전화번호를 모두 저장합니다.
     */
    private fun saveEmergencyContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().apply {
            // 각 연락처 타입의 선택 상태 저장
            putBoolean(KEY_CONTACT_119, binding.cb119.isChecked)
            putBoolean(KEY_CONTACT_FAMILY, binding.cbFamily.isChecked)
            putBoolean(KEY_CONTACT_FRIEND, binding.cbFriend.isChecked)

            // 가족 전화번호 저장 (체크된 경우에만)
            if (binding.cbFamily.isChecked) {
                putString(KEY_FAMILY_PHONE, binding.etFamilyPhone.text.toString().trim())
            }

            // 지인 전화번호 저장 (체크된 경우에만)
            if (binding.cbFriend.isChecked) {
                putString(KEY_FRIEND_PHONE, binding.etFriendPhone.text.toString().trim())
            }

            apply()  // 비동기로 저장 (commit()과 달리 UI 스레드를 블록하지 않음)
        }

        // 저장 완료 토스트 메시지 표시
        Toast.makeText(this, getString(R.string.contacts_saved), Toast.LENGTH_SHORT).show()
    }

    /**
     * 이전에 저장된 긴급 연락처 정보를 SharedPreferences에서 불러와 UI에 반영하는 함수
     * 앱 재실행 시 사용자가 이전에 설정한 연락처 정보를 복원합니다.
     */
    private fun loadSavedContacts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 119 체크박스 상태 복원
        binding.cb119.isChecked = prefs.getBoolean(KEY_CONTACT_119, false)

        // 가족 체크박스 및 전화번호 복원
        val isFamilyChecked = prefs.getBoolean(KEY_CONTACT_FAMILY, false)
        binding.cbFamily.isChecked = isFamilyChecked
        if (isFamilyChecked) {
            binding.tilFamily.visibility = View.VISIBLE
            binding.etFamilyPhone.setText(prefs.getString(KEY_FAMILY_PHONE, ""))
        }

        // 지인 체크박스 및 전화번호 복원
        val isFriendChecked = prefs.getBoolean(KEY_CONTACT_FRIEND, false)
        binding.cbFriend.isChecked = isFriendChecked
        if (isFriendChecked) {
            binding.tilFriend.visibility = View.VISIBLE
            binding.etFriendPhone.setText(prefs.getString(KEY_FRIEND_PHONE, ""))
        }
    }
}
