# 가디언 AI 비서 - 프로젝트 컨텍스트 문서

## 프로젝트 개요
**가디언 AI 비서**는 사용자의 상태를 실시간으로 모니터링하여 긴급 상황 발생 시 자동으로 대응하는 Android 애플리케이션입니다.

---

## 마일스톤 M1: 기초 골격 구축 (완료)

### 구현된 기능

#### 1. 권한 관리 시스템 (`PermissionManager.kt`)
- 일괄 권한 요청, 거부 시 설명 다이얼로그, 설정 화면 안내
- 등록된 권한: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACTIVITY_RECOGNITION, SEND_SMS, CALL_PHONE, POST_NOTIFICATIONS, FOREGROUND_SERVICE

#### 2. 메인 화면 (`MainActivity.kt`)
- 권한 상태 시각적 표시 (녹색/빨간색)
- 긴급 연락처(119, 가족, 지인) 다중 선택 및 SharedPreferences 저장

---

## 마일스톤 M2: 활동 감지 엔진 (완료)

### 구현된 기능

#### 1. 활동 감지 서비스 (`ActivityRecognitionService.kt`)
- Google Play Services Activity Recognition API 연동
- 포그라운드 서비스로 백그라운드 상시 모니터링
- 10초 주기로 사용자 활동 감지 (STILL, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, TILTING)
- 감지 결과를 브로드캐스트로 MainActivity에 전달
- `START_STICKY` 정책으로 시스템 종료 시 자동 재시작
- 서비스 종료 시 오래된 로그 자동 정리 (7일 이전)

#### 2. 이상 패턴 감지기 (`AnomalyDetector.kt`)
- **낙상 의심 감지**: 활발한 활동(걷기/달리기/자전거)에서 5초 이내 정지 상태로 급전환 감지
- **장시간 미활동 감지**: 30분 이상 STILL 상태 지속 시 이상으로 판단
- **신뢰도 필터링**: 감지 신뢰도 50% 미만 데이터 무시 (오탐 방지)
- **알림 쿨다운**: 동일 이상 패턴에 대해 3분 내 중복 알림 방지

#### 3. 알림 시스템 (`NotificationHelper.kt`)
- **모니터링 채널** (낮은 중요도): 포그라운드 서비스 상시 알림, 현재 활동 상태 실시간 표시
- **긴급 채널** (높은 중요도): 이상 패턴 감지 시 소리+진동 알림
- Android 8.0+ 알림 채널 자동 생성

#### 4. 데이터 저장소 (Room Database)
- **ActivityLogEntity**: 활동 유형, 신뢰도, 타임스탬프, 이상 여부, 이상 설명 저장
- **ActivityLogDao**: 최근 로그 조회, 시간 범위 조회, 이상 로그 조회, 오래된 로그 삭제
- **GuardianDatabase**: 싱글톤 패턴, fallbackToDestructiveMigration
- **ActivityLogRepository**: DAO 캡슐화, 7일 보존 기간 자동 정리

#### 5. MainActivity 업데이트
- 모니터링 시작/중지 토글 버튼 추가
- 현재 감지된 활동 상태 실시간 표시 (활동명 + 신뢰도%)
- 모니터링 상태 SharedPreferences 영속 저장/복원
- 활동 업데이트 BroadcastReceiver 등록/해제 (라이프사이클 연동)

### 사용된 기술 스택

| 분류 | 기술 | 버전/사양 |
|------|------|-----------|
| 언어 | Kotlin | 1.9.22 |
| 빌드 시스템 | Gradle (Kotlin DSL) | 8.5 |
| Android SDK | compileSdk 34, minSdk 26, targetSdk 34 | API 26~34 |
| UI 바인딩 | ViewBinding | - |
| UI 프레임워크 | Material Components | 1.11.0 |
| 활동 감지 | Google Play Services Location | 21.1.0 |
| 데이터베이스 | Room | 2.6.1 |
| 비동기 처리 | Kotlin Coroutines | 1.7.3 |
| 서비스 관리 | LifecycleService | 2.7.0 |
| 어노테이션 처리 | KSP | 1.9.22-1.0.17 |
| 데이터 저장 | SharedPreferences | Android 내장 |

### 프로젝트 구조
```
guardian-ai/
├── build.gradle.kts                    # 프로젝트 레벨 빌드 설정
├── settings.gradle.kts                 # 모듈 설정
├── gradle.properties                   # Gradle 속성
├── gradle/wrapper/                     # Gradle Wrapper
├── app/
│   ├── build.gradle.kts                # 앱 모듈 빌드 설정
│   ├── proguard-rules.pro              # ProGuard 규칙
│   └── src/main/
│       ├── AndroidManifest.xml         # 매니페스트 (권한 + 서비스)
│       ├── java/com/guardianai/assistant/
│       │   ├── MainActivity.kt                 # 메인 화면
│       │   ├── PermissionManager.kt            # 권한 관리
│       │   ├── ActivityRecognitionService.kt   # 활동 감지 포그라운드 서비스
│       │   ├── AnomalyDetector.kt              # 이상 패턴 감지기
│       │   ├── NotificationHelper.kt           # 알림 관리
│       │   └── data/
│       │       ├── ActivityLogEntity.kt        # Room 엔티티
│       │       ├── ActivityLogDao.kt           # Room DAO
│       │       ├── GuardianDatabase.kt         # Room Database
│       │       └── ActivityLogRepository.kt    # 레포지토리
│       └── res/
│           ├── layout/activity_main.xml        # 메인 레이아웃
│           ├── values/strings.xml              # 문자열 리소스
│           ├── values/colors.xml               # 색상 리소스
│           └── values/themes.xml               # 테마 설정
```

---

## 다음 단계: M3 - 긴급 대응 시스템

### 목표
이상 패턴 감지 시 설정된 긴급 연락처에 자동으로 SMS 전송 및 전화를 거는 긴급 대응 시스템을 구축합니다.

### 구현 예정 기능

#### 1. EmergencyResponder (`EmergencyResponder.kt`)
- 이상 감지 시 사용자 확인 단계 (30초 카운트다운)
- 사용자가 응답하지 않으면 자동으로 긴급 연락처에 SMS 발송
- SMS에 현재 GPS 위치 정보 포함
- 119 자동 전화 걸기 기능

#### 2. 위치 추적 연동 (`LocationTracker.kt`)
- FusedLocationProviderClient를 이용한 실시간 위치 추적
- 긴급 SMS 발송 시 Google Maps 링크 자동 생성
- 배터리 최적화를 위한 위치 정확도 동적 조절

#### 3. 사용자 확인 화면 (`EmergencyConfirmActivity.kt`)
- 전체 화면 오버레이 (잠금 화면 위에 표시)
- "괜찮습니다" 버튼으로 긴급 대응 취소
- 카운트다운 타이머 (시각적 + 소리)
- 미응답 시 자동 긴급 대응 실행

#### 4. 긴급 대응 이력 관리
- Room DB에 긴급 대응 이력 저장
- 오탐 통계 수집 (향후 감지 알고리즘 개선용)

### 아키텍처 고려사항
- WorkManager를 이용한 SMS 발송 재시도 (네트워크 불안정 대응)
- MVVM 패턴 도입 (ViewModel + StateFlow)
- 잠금 화면 위 액티비티 표시 (SHOW_WHEN_LOCKED, TURN_SCREEN_ON)
- 배터리 최적화 예외 요청 (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
