# WallPad / HomePad

2026-10-22 기준 홈패드 프로젝트의 **공개 가능한 복구용 소스와 설정 절차**입니다. R6850 OpenWrt, 월패드 보조 앱, 휴대폰 앱, Oracle Home Agent가 함께 동작합니다. 카메라 서버·펌웨어·녹화 파이프라인은 별도 프로젝트에서 관리합니다. 이 저장소의 휴대폰 CCTV 화면은 그 외부 서비스를 호출하는 클라이언트 코드만 포함합니다.

포함한 소스의 버전은 휴대폰 앱 `0.1.89`(versionCode 90), 월패드 브리지 `0.3.52`(55), Home Agent `0.14.32`입니다. 공개 저장용으로 개인값을 빌드·서버 설정으로 옮겼으므로 기존 설치 APK와 바이트 단위로 같지는 않습니다.

| 구성 요소 | 이 저장소의 위치 | 역할 |
| --- | --- | --- |
| R6850 OpenWrt | [`router/`](router/) | 홈넷 분리 연결, 승강기 상태/우리층 호출, 월패드 USB ADB 경로 |
| 월패드 보조 앱 | [`android/wallpadbridge/`](android/wallpadbridge/) | 기존 COMMAX 앱의 호출·사진·통화·문열림 이벤트 연동, 야간 벨소리 |
| 휴대폰 앱 | [`android/app/`](android/app/) | 방문, 승강기, 조명·난방, 주차, 위젯·알림, CCTV 클라이언트 |
| Home Agent | [`server/`](server/) | 앱 API, 공식 COMMAX/아파트아이 연동, 방문 기록·중계 |

원본 COMMAX 앱·월패드 펌웨어를 교체하는 저장소가 아닙니다. 공개 저장소이므로 **운영 키, 계정, 세대·장치 주소, 등록 차량, 방문 사진, APK, 패킷 캡처, 승강기 요청 바이너리**를 넣지 않았습니다. 초기화 복구에는 이 저장소와 별도로 보관한 개인 백업이 모두 필요합니다. [비공개 백업 항목](docs/PRIVATE_BACKUP.md)을 먼저 확인하세요.

복구 순서는 [초기화 후 재구축](docs/RESTORE.md), 월패드 세부 설정은 [월패드 구성](docs/WALLPAD.md)을 따릅니다. 현재 확인된 기능과 미완료 항목은 [상태표](docs/STATUS.md)에 구분했습니다.

## 빌드

Android SDK 35, JDK 17, Gradle 8.9가 필요합니다. `android/`에서 `gradlew.bat :app:assembleDebug :wallpadbridge:assembleDebug`를 실행합니다. 서버 주소·인증 토큰·주차 연락처 등은 로컬 환경변수로 주입합니다. 공개 소스의 초기값은 비워 두었습니다. [복구 절차](docs/RESTORE.md)에 변수 목록이 있습니다.

서버는 Python 3 표준 라이브러리를 중심으로 실행합니다. `server/config.example.json`의 `REPLACE_LOCALLY` 값을 개인 백업과 현재 장비 설정에 맞춰 채운 `config-server.json`을 서버에만 저장하고 `python3 home_agent.py --config config-server.json`으로 시작합니다. 공식 앱 계정과 세션은 공개 저장소에 저장하지 않습니다.
