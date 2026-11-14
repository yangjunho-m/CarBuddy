# 카버디 CarBuddy

차량 상태에 반응하는 자동차 캐릭터와 주차 후 읽는 주행 일기입니다. Java 기반 Android Automotive OS 앱이며 Pleos Vehicle SDK 2.3.2를 사용합니다.

## 실행

1. Android Studio에서 이 폴더를 엽니다. JDK 17과 Android SDK Platform 35가 필요합니다.
2. [Pleos Connect Emulator 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)에 따라 API 34의 Pleos Connect AVD를 만들고 Cold boot로 시작합니다.
3. Android Studio에서 `app`을 실행하거나 PowerShell에서 `./gradlew.bat :app:assembleDebug :app:lintDebug`를 실행합니다.
4. 빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`에 생깁니다. 바로 시험할 수 있는 사본은 `apk/CarBuddy-debug.apk`에 있습니다. APK는 Android Automotive용입니다.

## 에뮬레이터에서 시연하기

1. `adb forward tcp:8088 tcp:8088`을 실행한 뒤 [Virtual Console](http://127.0.0.1:8088/)을 브라우저에서 열어 둡니다.
2. 기어를 P에 두고 카버디를 실행합니다. Energy에서 Battery SOC와 Battery Energy를 함께 바꿉니다. 배터리 용량이 77.4 kWh일 때 72% / 55.7 kWh에서 18% / 13.9 kWh로 변경하면 낮은 잔량에 맞춰 표정이 바뀝니다.
3. 처음에는 주차 중 **주행 기록 권한 허용**을 누릅니다. 이 에뮬레이터는 차량 속도 권한을 위치 정보 권한으로 표시합니다. 앱은 GPS나 위치 기록을 사용하지 않습니다.
4. 주차 중 **모모카·디노카·냥카·포카**를 골라요. **표정 미리보기**에서 네 가지 반응을 확인하고 **자동 · 차량 상태 반영**으로 돌아갈 수 있습니다. 캐릭터 선택은 앱을 다시 실행해도 유지됩니다.
5. 기어를 D로 바꾸면 Pleos 주행 제한에 따라 앱 화면이 잠깁니다. 속도를 0으로 돌리고 기어를 P에 놓은 뒤 **일기 열기**를 누르면 거리, 이동 시간, 남은 배터리와 함께 주행 중 감지한 속도 초과와 급감속을 확인할 수 있습니다.

## 검증 범위

- Pleos Connect v2.1.0 이미지, Android API 34, Vehicle Service 2.3.22, 1920×1080 에뮬레이터에서 설치와 실행을 확인했습니다.
- [공모전 시연 영상](demo/CarBuddy-contest-demo.mp4)에서 Pleos 에뮬레이터의 배터리 72% → 18% 표정 변화, 주행 중 잠금, 주차 후 일기(67 m, 6초)를 확인할 수 있습니다.
- 이 이미지에서는 Vehicle SDK의 현재 트립 거리·시간 조회가 미지원입니다. 해당 값이 없으면 Android Automotive의 속도와 기어 신호를 읽어 P에서 출발한 뒤 다시 P에 도착할 때까지의 거리와 움직인 시간을 계산합니다.
- 계산값은 추정치이며, 주행 전에 앱을 실행하고 권한을 허용해야 합니다. 속도 신호가 5초 넘게 끊기거나 앱이 종료되어 기록을 잃으면 그 주행의 계산값은 제공하지 않습니다. 하루치 기록은 합산하지 않습니다.
- 속도 초과 표정은 앱에서 정한 80 km/h 기준을 사용하고, 급감속 표정은 연속 속도 샘플의 감속도가 3.5 m/s² 이상일 때 기록합니다. 두 반응은 주차 후 표시합니다. 배터리 부족은 20% 이하, 음악 재생은 Android에서 음악으로 분류한 활성 미디어 신호를 사용합니다.
- 실차·상용 서명·스토어 심사는 검증하지 않았습니다. 경적·노면 충격·주차 센서 반응은 범위에서 제외했습니다.

## 코드 확인

```powershell
javac -encoding UTF-8 -d app/build/trip-check app/src/main/java/app/carbuddy/TripStory.java app/src/main/java/app/carbuddy/TripMeter.java checks/TripStoryCheck.java
java -ea -cp app/build/trip-check app.carbuddy.TripStoryCheck
```

로컬 접수 양식에는 팀 연락처가 들어 있어 Git에서 제외했습니다. 접수에는 각 팀원이 직접 동의 여부·서명·날짜를 작성해야 합니다. 테스트 APK는 공식 제출용 release APK가 아닙니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
