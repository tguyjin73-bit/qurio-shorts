package com.guardianai.assistant

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
 * 이 화면은 두 가지 핵심 기능을 제공합니다:
 * 1. 권한 승인 상태를 시각적으로 표시 (허용/거부 상태를 색상으로 구분)
 * 2. 긴급 연락처(119, 가족, 지인)를 다중 선택하여 SharedPreferences에 저장
 */
class MainActivity : AppCompatActivity() {

    /** ViewBinding 인스턴스 - 레이아웃의 모든 뷰에 타입 안전하게 접근 */
    private lateinit var binding: ActivityMainBinding

    /** 권한 관리자 인스턴스 */
    private lateinit var permissionManager: PermissionManager

    /** SharedPreferences 키 상수 정의 */
    companion object {
        private const val PREFS_NAME = "guardian_ai_prefs"          // SharedPreferences 파일명
        private const val KEY_CONTACT_119 = "contact_119"           // 119 선택 여부
        private const val KEY_CONTACT_FAMILY = "contact_family"     // 가족 선택 여부
        private const val KEY_CONTACT_FRIEND = "contact_friend"     // 지인 선택 여부
        private const val KEY_FAMILY_PHONE = "family_phone"         // 가족 전화번호
        private const val KEY_FRIEND_PHONE = "friend_phone"         // 지인 전화번호
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

        // UI 초기 설정
        setupToolbar()
        setupPermissionButton()
        setupEmergencyContacts()
        loadSavedContacts()

        // 현재 권한 상태를 UI에 반영
        updatePermissionStatusUI()
    }

    /**
     * 액티비티가 다시 화면에 표시될 때 호출되는 라이프사이클 콜백
     * 설정 화면에서 돌아왔을 때 권한 상태가 변경되었을 수 있으므로 UI를 갱신합니다.
     */
    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아온 경우 권한 상태가 변경되었을 수 있음
        updatePermissionStatusUI()
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
