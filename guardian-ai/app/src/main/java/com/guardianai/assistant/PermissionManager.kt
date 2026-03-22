package com.guardianai.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * PermissionManager - 가디언 AI 비서에 필요한 모든 권한을 관리하는 클래스
 *
 * 앱 실행 시 필요한 모든 권한을 한 번에 요청하고,
 * 사용자가 거부할 경우 권한의 필요성을 설명하는 다이얼로그를 표시합니다.
 */
class PermissionManager(private val activity: Activity) {

    companion object {
        /** 권한 요청 코드 상수 */
        const val PERMISSION_REQUEST_CODE = 1001

        /**
         * 앱에서 요청해야 하는 모든 필수 권한 목록을 반환하는 함수
         * Android 13(Tiramisu) 이상에서는 POST_NOTIFICATIONS 권한이 추가됨
         */
        fun getRequiredPermissions(): Array<String> {
            // 기본 권한 목록 (모든 Android 버전 공통)
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,      // 정밀 위치
                Manifest.permission.ACCESS_COARSE_LOCATION,    // 대략적 위치
                Manifest.permission.ACTIVITY_RECOGNITION,      // 활동 감지
                Manifest.permission.SEND_SMS,                  // SMS 전송
                Manifest.permission.CALL_PHONE                 // 전화 걸기
            )

            // Android 13(API 33, Tiramisu) 이상에서만 POST_NOTIFICATIONS 권한 추가
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            return permissions.toTypedArray()
        }
    }

    /**
     * 개별 권한의 허용 여부를 확인하는 함수
     *
     * @param permission 확인할 권한 문자열 (예: Manifest.permission.ACCESS_FINE_LOCATION)
     * @return 권한이 허용되었으면 true, 거부되었으면 false
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 모든 필수 권한이 허용되었는지 일괄 확인하는 함수
     *
     * @return 모든 권한이 허용되었으면 true, 하나라도 거부되었으면 false
     */
    fun areAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(it) }
    }

    /**
     * 아직 허용되지 않은 권한 목록을 반환하는 함수
     * 권한 요청 시 이미 허용된 권한은 제외하고 거부된 권한만 요청하기 위해 사용
     *
     * @return 아직 허용되지 않은 권한 문자열 배열
     */
    fun getDeniedPermissions(): Array<String> {
        return getRequiredPermissions().filter { !isPermissionGranted(it) }.toTypedArray()
    }

    /**
     * 거부된 권한들을 일괄 요청하는 함수
     * 이미 모든 권한이 허용된 경우에는 콜백을 즉시 호출하고,
     * 거부된 권한이 있으면 시스템 권한 요청 다이얼로그를 표시합니다.
     *
     * @param launcher ActivityResultLauncher - 권한 요청 결과를 받기 위한 런처
     */
    fun requestAllPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        val deniedPermissions = getDeniedPermissions()

        if (deniedPermissions.isEmpty()) {
            // 모든 권한이 이미 허용된 경우 - 별도 처리 불필요
            return
        }

        // 사용자가 이전에 권한을 거부한 적이 있는지 확인
        val shouldShowRationale = deniedPermissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }

        if (shouldShowRationale) {
            // 이전에 거부한 적이 있으면 설명 다이얼로그를 먼저 표시
            showPermissionRationaleDialog(launcher, deniedPermissions)
        } else {
            // 처음 요청하는 경우 바로 시스템 권한 다이얼로그 표시
            launcher.launch(deniedPermissions)
        }
    }

    /**
     * 권한 필요성을 설명하는 다이얼로그를 표시하는 함수
     * 사용자가 이전에 권한을 거부한 경우 왜 해당 권한이 필요한지 설명합니다.
     *
     * @param launcher ActivityResultLauncher - 다이얼로그 확인 후 권한 재요청에 사용
     * @param permissions 요청할 권한 배열
     */
    private fun showPermissionRationaleDialog(
        launcher: ActivityResultLauncher<Array<String>>,
        permissions: Array<String>
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.permission_title))
            .setMessage(activity.getString(R.string.permission_message))
            .setPositiveButton(activity.getString(R.string.permission_grant)) { dialog, _ ->
                // 사용자가 "권한 허용" 버튼을 누르면 권한 재요청
                dialog.dismiss()
                launcher.launch(permissions)
            }
            .setNegativeButton(activity.getString(R.string.permission_deny)) { dialog, _ ->
                // 사용자가 "나중에" 버튼을 누르면 다이얼로그 닫기
                dialog.dismiss()
            }
            .setCancelable(false)  // 뒤로가기 버튼으로 닫기 방지
            .show()
    }

    /**
     * 앱 설정 화면으로 이동하도록 안내하는 다이얼로그를 표시하는 함수
     * 사용자가 "다시 묻지 않기"를 선택한 경우, 시스템 권한 다이얼로그를 표시할 수 없으므로
     * 앱 설정 화면에서 직접 권한을 허용하도록 안내합니다.
     */
    fun showSettingsDialog() {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.permission_title))
            .setMessage(activity.getString(R.string.permission_settings_message))
            .setPositiveButton(activity.getString(R.string.go_to_settings)) { dialog, _ ->
                dialog.dismiss()
                // 앱 설정 화면으로 이동하는 인텐트 생성 및 실행
                openAppSettings()
            }
            .setNegativeButton(activity.getString(R.string.permission_deny)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 앱의 시스템 설정 화면을 여는 함수
     * 사용자가 직접 권한을 토글할 수 있는 설정 화면으로 이동합니다.
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * 특정 권한에 대해 사용자가 "다시 묻지 않기"를 선택했는지 확인하는 함수
     * shouldShowRequestPermissionRationale()이 false를 반환하고
     * 동시에 권한이 거부된 상태라면 "다시 묻지 않기"를 선택한 것으로 판단합니다.
     *
     * @param permission 확인할 권한 문자열
     * @return "다시 묻지 않기"를 선택했으면 true
     */
    fun isPermissionPermanentlyDenied(permission: String): Boolean {
        return !isPermissionGranted(permission) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}
