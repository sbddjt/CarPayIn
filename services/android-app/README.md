# android-app

Car Pay-in AAOS(Android Automotive OS) 차량 앱입니다.

## 담당 기능

- QR 로그인 세션 생성 및 완료 폴링
- 현대차 차량 확인 및 앱 토큰 로컬 저장
- Mock PG WebView를 통한 카드 등록
- 파트너 주차장 조회 및 주차 사전 알림 전송
- AWS IoT Core MQTT로 입차·결제 알림 수신
- 차량 내 주차·토큰·거래 상태 로컬 관리

## 폴더 구조

```
app/src/main/java/com/example/carpayin/
  config/       BuildConfig 기반 런타임 설정
  data/         토큰·주차·거래 로컬 저장소
  network/      백엔드 API·MQTT 클라이언트
  service/      주차·MQTT 상태 포그라운드 서비스
  ui/           메인·등록·카드 등록·개발자 UI
  vehicle/      차량·지오펜스·내비게이션 헬퍼

app/src/main/res/
  drawable*/    UI 배경·런처 에셋·카드 로고
  layout/       XML 레이아웃
  values/       색상·문자열·테마
```

## 로컬 설정

`local.properties.example`을 복사합니다:

```powershell
Copy-Item services\android-app\local.properties.example services\android-app\local.properties
```

로컬 에뮬레이터 기본값:

```text
CARPAYIN_BACKEND_BASE_URL=http://10.0.2.2:8000
CARPAYIN_QR_BASE_URL=https://your-ngrok-domain.ngrok-free.app
CARPAYIN_MQTT_BROKER_URL=tcp://10.0.2.2:1883
CARPAYIN_EMULATOR_LOCALHOST_REWRITE=true
```

현대차 OAuth 콜백을 받으려면 `CARPAYIN_QR_BASE_URL`에 ngrok 등 외부 URL을 사용합니다.

## 빌드

디버그 Kotlin 소스 컴파일:

```powershell
cd services\android-app
.\gradlew.bat :app:compileDebugKotlin
```

단위 테스트 실행:

```powershell
cd services\android-app
.\gradlew.bat testDebugUnitTest
```

## 백엔드 연동 엔드포인트

```
POST /auth/qr-session
GET  /auth/session/{session_id}/status
POST /auth/confirm-car
POST /auth/refresh
POST /card/order
GET  /parking/lots
POST /parking/navigate
GET  /fee/{session_id}
POST /payment
```

OpenAPI 명세: `../../docs/api/car-pay-in-openapi.yaml`

## AWS 플레이버 (실기기 / 시연)

`aws` 빌드 플레이버로 실제 AWS 인프라에 연결합니다:

```text
# services/android-app/local.properties (빌드 환경 전용, 커밋 금지)
CARPAYIN_BACKEND_BASE_URL=https://<carpayin-backend-alb-domain>
CARPAYIN_QR_BASE_URL=https://<carpayin-backend-alb-domain>
IOT_ENDPOINT=<aws-iot-data-endpoint>.iot.ap-northeast-2.amazonaws.com
COGNITO_IDENTITY_POOL_ID=ap-northeast-2:<cognito-identity-pool-id>
```

MQTT 연결 흐름:

```
앱 시작
  └─ Cognito Identity Pool → 임시 AWS 자격증명 발급
  └─ AWS IoT Core MQTT 연결
  └─ 토픽 구독: car/{car_id}/parking

입차 확인 시
  carpayin-backend → SQS → Lambda → IoT Core publish
  └─ 앱 MQTT 수신 → 입차 완료 알림 표시
```

차량 통신에서 MQTT가 표준 프로토콜이므로, 푸시 알림도 IoT Core MQTT로 통일했습니다.

## 로컬 실행 순서

1. 루트 Docker Compose 스택 실행
2. `PUBLIC_BASE_URL`과 Android `CARPAYIN_QR_BASE_URL`을 OAuth 콜백을 받을 수 있는 공개 URL로 설정
3. Android 앱 컴파일 및 실행
4. QR URL 스캔 또는 열기 → 현대차 OAuth 완료 → 앱이 `/auth/session/{session_id}/status` 폴링
5. 차량 확인 → 카드 등록 → 내비게이션·지오펜스 흐름으로 `/parking/navigate` 및 결제 트리거

## 커밋 제외 항목

로컬 스크린샷, 뷰 계층 덤프, `.gradle/`, 빌드 결과물, `local.properties`는 커밋하지 않습니다.
