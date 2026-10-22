# 초기화 후 재구축 순서

이 문서는 **기존 COMMAX 월패드의 기본 기능을 유지**하면서 R6850·서버·휴대폰 앱을 되살리는 순서입니다. 공개 소스만으로 운영 키나 현장별 SOAP 요청, 출입구 주소를 복원할 수는 없습니다. 먼저 [비공개 백업](PRIVATE_BACKUP.md)과 올바른 R6850 펌웨어를 확보합니다. 초기화 전이면 현재 설정/이미지의 해시와 서비스 상태를 따로 기록합니다.

## 1. R6850 OpenWrt

1. R6850 하드웨어 버전과 펌웨어 이미지를 확인한 뒤 해당 이미지로 설치합니다. 관리용 **LAN1**은 남기고, 가정용 인터넷(2.4GHz 상위 연결)과 관리용 5GHz는 분리합니다. 홈넷 측 연결은 `wan`, 월패드 연결은 `lan3`의 `br-homenet`으로 구성합니다. LAN2/4 비활성화 등 전원·발열 설정은 기존 백업과 대조해 적용합니다. 가정용 LAN과 홈넷을 일반 라우팅으로 섞지 않습니다.
2. 비공개 OpenWrt 백업의 `network`, `wireless`, `firewall`, `dropbear`, WireGuard 설정을 장치에 맞게 복원합니다. 새 키를 썼다면 Oracle/휴대폰 피어도 함께 교체합니다. ADB·승강기 서비스에 필요한 `socat`, `nft`, `uhttpd`, `ethtool`, `adb`의 펌웨어 호환 버전을 설치합니다. 펌웨어별 패키지 관리자는 다를 수 있습니다.
3. `router/openwrt-homenet-phy-v2.init`, `openwrt-homenet-group-forward.init` 및 `.hotplug`을 각각 기존 서비스/핫플러그 위치에 설치합니다. PHY 스크립트의 `lan3`/`wan` 매핑을 실제 장치와 대조합니다. 그룹 전달 마스크는 `br-homenet`에만 적용합니다.
4. `router/elevator.env.example`을 `/etc/elevator.env`로 복사해 **실제 홈넷 서버·월패드 주소**를 개인 백업에서 입력하고 0600으로 제한합니다. 같은 백업의 `getEvStatus-request.bin`과 `setEvStatus-request.bin`을 `/usr/share/elevator/`에 두며, `/etc/elevator-call.token`은 새 랜덤 토큰 또는 백업 값으로 설치합니다. 요청 바이너리는 세대별 값이므로 이 저장소에는 없습니다.
5. `elevator-status-poller`는 `/usr/libexec/`, `elevator-call.cgi`는 `/www/elevator/cgi-bin/`, `index.html`은 `/www/elevator/`, 해당 `.init`은 `/etc/init.d/`로 배치합니다. 실행 권한과 파일 소유권을 점검한 뒤 **상태 조회부터** 시작합니다. `/tmp/elevator-status-poller/health.json`의 `lastOk`, 새 `lastSuccessEpoch`, 서버 응답을 확인한 후 한 번만 우리층 호출을 현장에서 시험합니다. 결과 불명확 시 자동 재전송하지 않습니다.
6. 월패드 USB가 R6850에 연결된 경우 `router/wallpad-adb.init`을 `/etc/init.d/wallpad-adb`로 설치합니다. `adb` 서버는 `127.0.0.1:5037`로만 수신하고, 외부 연결은 인증된 SSH 경로를 이용합니다. `adb devices -l`로 실제 월패드를 식별하고 다른 Android 기기에 설치하지 않도록 확인합니다. ADB를 인터넷에 직접 노출하지 않습니다.

## 2. Oracle Home Agent

1. 서버의 `/opt/homepad-agent/`에 [`server/`](../server/)의 Python 소스와 `static/`을 복사합니다. `server/config.example.json`을 참고해 **비공개** `config-server.json`을 작성합니다. `REPLACE_LOCALLY`는 운영값을 채워야 하며, `cctv` 부분은 별도 카메라 프로젝트에 맞춰 설정합니다. 처음에는 쓰기 제어를 꺼 둔 채 읽기 API를 확인합니다.
2. `HOMEPAD_COMMAX_CLIENT_SECRET`을 서버의 비공개 서비스 환경에 설정합니다. 운영 계정·세션·토큰은 서버에만 저장하고 세션 파일을 0600으로 제한합니다. 공식 COMMAX 연동은 로그인 후 실제 장치 목록과 난방·조명 채널을 읽어 확인합니다. 아파트아이 네이버 로그인은 인증 성공과 **주차 API 연결 확인**을 별도로 검증합니다.
3. `homepad-agent.service`를 운영 사용자/경로에 맞게 조정하고 시작합니다. 필요한 중앙현관 UC 중계는 `uc-bridge.example.json`에서 개인 설정을 복원해 `uc-bridge.json`으로 저장한 뒤 `homepad-uc-bridge.service`를 추가합니다. HTTPS 프록시와 인증서는 서버 개인 백업 또는 새 발급본을 사용합니다.
4. `GET /api/status`, 인증된 `GET /api/events`, 주차 상태, 승강기 상태의 읽기 동작을 확인합니다. 서비스가 실행 중이거나 HTTP 200만 나온다고 물리 호출·사진·통화·문열림이 입증되지는 않습니다.

## 3. 월패드와 휴대폰

1. [월패드 구성](WALLPAD.md)의 순서로 USB ADB에서 장치 모델·기본 홈 화면·COMMAX 기능을 확인한 뒤 `wallpadbridge` APK를 설치합니다. 재부팅 후에도 보조 앱이 설치·실행 중인지 확인합니다. 한 현장 점검에서는 재부팅 후 보조 앱이 사라져 재설치가 필요했으며 원인은 아직 확정되지 않았습니다. 벨소리와 자동 개방은 기존 설정을 먼저 읽고 복원합니다.
2. JDK 17과 Android SDK 35에서 `android/gradlew.bat :app:assembleDebug :wallpadbridge:assembleDebug`로 빌드합니다. 로컬 빌드 환경변수: `HOMEPAD_SERVER_URL`, `HOMEPAD_API_TOKEN`, `HOMEPAD_INGEST_TOKEN`, `HOMEPAD_COMMAX_CLIENT_SECRET`(서버 전용), `HOMEPAD_PARKING_VISITOR_NAME`, `HOMEPAD_PARKING_PHONE`. 카메라 화면을 쓰려면 별도 프로젝트의 `HOMEPAD_CCTV_ORIGIN`도 지정합니다. 빌드 결과 APK는 공개 저장소에 넣지 않습니다.
3. 휴대폰 앱의 설정에서 서버 URL/API 토큰을 확인하고 알림·마이크·배터리 예외·위젯 권한을 설정합니다. 네이버 웹 로그인 후 실제 주차 조회가 되는지 확인합니다. 측면 버튼/뒤 두드리기는 RegiStar·Tap, Tap·Tasker 등의 **휴대폰 개인 설정**을 별도로 복원합니다.
4. 기본 홈·전등/난방 조회→승강기 조회→방문사진→양방향 음성→현장 문열기 순서로 검증합니다. 3개 중앙현관과 세대현관은 각각 별도로 확인하고, 자동 개방은 현장 시험을 마친 뒤 켭니다. 야간 음량 예약은 22:00/02:00 자연 경계와 재부팅 후에도 확인합니다.

## 복구 완료 판정

앱·서버·월패드 버전, 각 서비스의 시작/재시작 상태, 이벤트·사진 지연, 양방향 실제 청감, 각 문 물리 개방, 승강기 실제 이동, 주차 실제 입차 기록을 따로 기록합니다. [미완료 기능](STATUS.md)을 복구 성공으로 판정하지 않습니다.
