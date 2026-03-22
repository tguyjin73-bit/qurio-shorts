# 가디언 AI 비서 - 프로젝트 컨텍스트 문서

## 프로젝트 개요
**가디언 AI 비서**는 사용자의 상태를 실시간으로 모니터링하여 긴급 상황 발생 시 자동으로 대응하는 Android 애플리케이션입니다.

## 현재 마일스톤: M1 (기초 골격 구축)

### 구현된 기능

#### 1. 권한 관리 시스템 (`PermissionManager.kt`)
- **일괄 권한 요청**: 앱 실행 시 필요한 모든 권한을 한 번에 요청
- **설명 다이얼로그**: 사용자가 권한을 거부한 경우 왜 해당 권한이 필요한지 설명하는 다이얼로그 표시
- **설정 화면 안내**: "다시 묻지 않기"를 선택한 경우 앱 설정 화면으로 이동하도록 안내
- **등록된 권한 목록**:
  | 권한 | 용도 |
  |------|------|
  | `ACCESS_FINE_LOCATION` | 긴급 상황 시 정밀 위치 정보 전송 |
  | `ACCESS_COARSE_LOCATION` | 대략적 위치 파악 |
  | `ACTIVITY_RECOGNITION` | 걷기, 달리기, 정지 등 활동 상태 감지 |
  | `SEND_SMS` | 긴급 연락처에 SMS 자동 전송 |
  | `CALL_PHONE` | 긴급 연락처에 자동 전화 |
  | `POST_NOTIFICATIONS` | 위험 상황 알림 표시 (Android 13+) |
  | `FOREGROUND_SERVICE` | 백그라운드 상시 모니터링 |

#### 2. 메인 화면 (`MainActivity.kt`)
- **권한 상태 시각적 표시**: 각 권한의 허용/거부 상태를 녹색/빨간색으로 구분하여 실시간 표시
- **권한 요청 버튼**: 거부된 권한을 한 번에 재요청할 수 있는 버튼
- **긴급 연락처 관리**:
  - 119(소방/응급), 가족, 지인을 다중 선택 가능
  - 가족/지인 선택 시 전화번호 입력 필드 동적 표시
  - SharedPreferences를 이용한 데이터 영속 저장
  - 앱 재실행 시 이전 설정 자동 복원

### 사용된 기술 스택

| 분류 | 기술 | 버전/사양 |
|------|------|-----------|
| 언어 | Kotlin | 1.9.22 |
| 빌드 시스템 | Gradle (Kotlin DSL) | 8.5 |
| Android SDK | compileSdk 34, minSdk 26, targetSdk 34 | API 26~34 |
| UI 바인딩 | ViewBinding | - |
| UI 프레임워크 | Material Components | 1.11.0 |
| 레이아웃 | ConstraintLayout, ScrollView | 2.1.4 |
| 데이터 저장 | SharedPreferences | Android 내장 |
| 권한 관리 | ActivityResultContracts (Activity 1.8.2) | - |

### 프로젝트 구조
```
guardian-ai/
├── build.gradle.kts              # 프로젝트 레벨 빌드 설정
├── settings.gradle.kts           # 모듈 설정
├── gradle.properties             # Gradle 속성
├── gradle/wrapper/               # Gradle Wrapper
├── app/
│   ├── build.gradle.kts          # 앱 모듈 빌드 설정 (ViewBinding 활성화)
│   ├── proguard-rules.pro        # ProGuard 규칙
│   └── src/main/
│       ├── AndroidManifest.xml   # 매니페스트 (권한 등록)
│       ├── java/com/guardianai/assistant/
│       │   ├── MainActivity.kt       # 메인 화면 (권한 상태 + 긴급 연락처)
│       │   └── PermissionManager.kt  # 권한 관리 유틸리티
│       └── res/
│           ├── layout/activity_main.xml  # 메인 화면 레이아웃
│           ├── values/strings.xml        # 문자열 리소스 (한글)
│           ├── values/colors.xml         # 색상 리소스
│           └── values/themes.xml         # 테마 설정
```

---

## 다음 단계: M2 - 활동 감지 엔진

### 목표
ACTIVITY_RECOGNITION 권한을 활용하여 사용자의 실시간 활동 상태를 감지하고, 이상 패턴을 분석하는 엔진을 구축합니다.

### 구현 예정 기능

#### 1. 활동 감지 서비스 (`ActivityRecognitionService.kt`)
- Google Play Services의 Activity Recognition API 연동
- `DetectedActivity` 기반 활동 유형 분류:
  - `STILL` (정지), `WALKING` (걷기), `RUNNING` (달리기)
  - `IN_VEHICLE` (차량 탑승), `ON_BICYCLE` (자전거)
  - `TILTING` (기울임), `UNKNOWN` (알 수 없음)
- 활동 전환(Transition) 이벤트 감지
- 포그라운드 서비스로 구현하여 백그라운드에서도 지속 동작

#### 2. 이상 패턴 감지 (`AnomalyDetector.kt`)
- 급격한 활동 변화 감지 (예: WALKING → STILL 급전환 = 낙상 의심)
- 장시간 미활동 감지 (설정 가능한 임계 시간)
- 비정상적 위치 이동 패턴 감지

#### 3. 알림 시스템 (`NotificationHelper.kt`)
- 포그라운드 서비스 상시 알림 (모니터링 중 표시)
- 위험 상황 감지 시 긴급 알림 발송
- 알림 채널(Notification Channel) 설정 (Android 8.0+)

#### 4. 데이터 저장소 (`ActivityLogRepository.kt`)
- Room Database를 활용한 활동 로그 영속 저장
- 활동 이력 조회 및 통계 기능
- 데이터 보존 기간 설정 (자동 삭제)

### 추가할 의존성
```kotlin
// Google Play Services - Activity Recognition
implementation("com.google.android.gms:play-services-location:21.1.0")

// Room Database
implementation("androidx.room:room-runtime:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")

// Coroutines (비동기 처리)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Lifecycle (서비스 관리)
implementation("androidx.lifecycle:lifecycle-service:2.7.0")
```

### 아키텍처 고려사항
- MVVM 패턴 도입 검토 (ViewModel + LiveData/StateFlow)
- Repository 패턴으로 데이터 레이어 분리
- WorkManager를 이용한 주기적 데이터 정리 작업
- 배터리 최적화를 위한 감지 주기 동적 조절
