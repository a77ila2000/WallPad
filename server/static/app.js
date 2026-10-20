const zonesElement = document.querySelector('#zones');
const connectionElement = document.querySelector('#connection');
const updatedElement = document.querySelector('#updated');
const noticeElement = document.querySelector('#notice');
const allTemperatureElement = document.querySelector('#all-temperature');
const allOnButton = document.querySelector('#all-on');
const allOffButton = document.querySelector('#all-off');
const applyAllButton = document.querySelector('#apply-all-temperature');
const batchSwitchToggle = document.querySelector('#batch-switch-toggle');
const batchSwitchState = document.querySelector('#batch-switch-state');
const lightsList = document.querySelector('#lights-list');
const cctvState = document.querySelector('#cctv-state');
const cctvChannels = document.querySelector('#cctv-channels');
const cctvPreviewWrap = document.querySelector('#cctv-preview-wrap');
const cctvPreview = document.querySelector('#cctv-preview');
const cctvLoading = document.querySelector('#cctv-loading');
const cctvStop = document.querySelector('#cctv-stop');
const elevatorCallButton = document.querySelector('#elevator-call');
const elevatorCallState = document.querySelector('#elevator-call-state');
const visitorCard = document.querySelector('#visitor-card');
const visitorTitle = document.querySelector('#visitor-title');
const visitorState = document.querySelector('#visitor-state');
const visitorNotificationButton = document.querySelector('#visitor-notification');
const incomingCall = document.querySelector('#incoming-call');
const incomingCallTitle = document.querySelector('#incoming-call-title');
const incomingCallLocation = document.querySelector('#incoming-call-location');
const incomingCallTime = document.querySelector('#incoming-call-time');
const incomingCallStatus = document.querySelector('#incoming-call-status');
const incomingCallPhoto = document.querySelector('#incoming-call-photo');
const incomingCallDismiss = document.querySelector('#incoming-call-dismiss');
const incomingCallUnlock = document.querySelector('#incoming-call-unlock');
const incomingCallUnlockReason = document.querySelector('#incoming-call-unlock-reason');
let lastSequence = 0;
let refreshing = false;
let latestStatus = null;
let cctvChannel = null;
let cctvChannelName = '';
let cctvTimer = null;
let cctvObjectUrl = null;
let cctvRequestInFlight = false;
let allTemperature = 24;
let visitorInitialized = false;
let lastVisitorEventId = null;
let dismissedVisitorEventId = null;
let visitorPhotoObjectUrl = null;
let visitorPhotoPath = null;

const fragment = new URLSearchParams(window.location.hash.slice(1));
const enrollmentToken = fragment.get('token');
if (enrollmentToken) {
  localStorage.setItem('homepad.apiToken', enrollmentToken);
  history.replaceState(null, '', `${window.location.pathname}${window.location.search}`);
}
const apiToken = localStorage.getItem('homepad.apiToken') ?? '';

function apiFetch(url, options = {}) {
  const headers = new Headers(options.headers ?? {});
  if (apiToken) headers.set('Authorization', `Bearer ${apiToken}`);
  return fetch(url, {...options, headers});
}

function h(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function showNotice(message) {
  noticeElement.hidden = false;
  noticeElement.textContent = message;
}

function setAllTemperature(value) {
  allTemperature = Math.max(5, Math.min(40, value));
  allTemperatureElement.replaceChildren(String(allTemperature), h('sup', '', '°'));
}

function formatVisitorTime(value) {
  const parsed = Date.parse(value ?? '');
  return Number.isFinite(parsed) ? new Date(parsed).toLocaleTimeString('ko-KR') : '';
}

function visitorLocation(event) {
  if (event?.location) return event.location;
  if (event?.sourceId === 'lobby0041') return '1층 중앙현관';
  if (event?.kind === 'unit_entrance') return '세대 현관';
  return event?.title ?? '중앙현관';
}

function notifyVisitor(event) {
  if (navigator.vibrate) navigator.vibrate([300, 160, 300, 160, 500]);
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  const options = {
    body: `${formatVisitorTime(event.receivedAt)} · ${visitorLocation(event)}`,
    icon: '/icon.svg',
    tag: 'homepad-visitor-ring',
    renotify: true,
  };
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.ready.then(registration => registration.showNotification(event.title ?? '방문 호출', options)).catch(() => {});
  } else {
    new Notification(event.title ?? '방문 호출', options);
  }
}

async function renderVisitorPhoto(path) {
  if (!path) {
    incomingCallPhoto.hidden = true;
    incomingCallPhoto.removeAttribute('src');
    visitorPhotoPath = null;
    if (visitorPhotoObjectUrl) URL.revokeObjectURL(visitorPhotoObjectUrl);
    visitorPhotoObjectUrl = null;
    return;
  }
  if (path === visitorPhotoPath && visitorPhotoObjectUrl) {
    incomingCallPhoto.hidden = false;
    return;
  }
  try {
    const response = await apiFetch(path, {cache: 'no-store'});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const nextUrl = URL.createObjectURL(await response.blob());
    if (visitorPhotoObjectUrl) URL.revokeObjectURL(visitorPhotoObjectUrl);
    visitorPhotoObjectUrl = nextUrl;
    visitorPhotoPath = path;
    incomingCallPhoto.src = nextUrl;
    incomingCallPhoto.hidden = false;
  } catch (_) {
    incomingCallPhoto.hidden = true;
  }
}

function renderVisitor(status) {
  const visitors = status.visitors ?? {};
  const event = visitors.lastEvent;
  const ringing = Boolean(visitors.ringing && event);
  visitorCard.classList.toggle('ringing', ringing);
  const location = visitorLocation(event);
  visitorTitle.textContent = ringing ? `${location} 호출 중` : '호출 대기';
  visitorState.textContent = ringing
    ? `${formatVisitorTime(event.receivedAt)} · ${event.title}`
    : event
      ? `마지막 호출 ${formatVisitorTime(event.receivedAt)} · ${location}`
      : status.capabilities?.visitorCalls
        ? '새 방문 호출을 기다립니다'
        : '호출 감지기 연결 준비 중';

  const eventId = event?.id ?? null;
  if (visitorInitialized && eventId && eventId !== lastVisitorEventId) notifyVisitor(event);
  lastVisitorEventId = eventId;
  visitorInitialized = true;

  const centralCall = event?.kind === 'central_entrance';
  const doorCapability = centralCall ? 'centralDoorUnlock' : 'unitDoorUnlock';
  const doorReady = Boolean(status.capabilities?.[doorCapability]);
  const doorReason = status.capabilityDetails?.[doorCapability]?.reason
    ?? '문열림 제어 경로를 분석하고 있습니다.';
  const showIncomingCall = ringing && eventId && eventId !== dismissedVisitorEventId;
  incomingCall.hidden = !showIncomingCall;
  if (showIncomingCall) {
    incomingCall.dataset.eventId = eventId;
    incomingCallTitle.textContent = event.kind === 'central_entrance' ? '중앙현관 호출 수신' : '세대 현관 호출 수신';
    incomingCallLocation.textContent = location;
    incomingCallTime.textContent = formatVisitorTime(event.receivedAt);
    incomingCallStatus.textContent = '월패드 호출 신호를 실시간으로 감지했습니다';
    renderVisitorPhoto(event.photoUrl ?? '');
    incomingCallUnlock.disabled = !doorReady;
    incomingCallUnlock.textContent = doorReady ? '문 열기' : '문 열기 준비 중';
    incomingCallUnlockReason.textContent = doorReady
      ? '현재 호출 세션에서 한 번만 실행합니다.'
      : doorReason;
  } else {
    renderVisitorPhoto('');
  }

  if ('Notification' in window && Notification.permission !== 'granted') {
    visitorNotificationButton.hidden = false;
    visitorNotificationButton.textContent = Notification.permission === 'denied' ? '알림 권한이 차단됨' : '휴대폰 알림 허용';
    visitorNotificationButton.disabled = Notification.permission === 'denied';
  } else {
    visitorNotificationButton.hidden = true;
  }
}

function zoneCard(zone, controlEnabled) {
  const card = h('article', 'zone-row');
  const summary = h('div', 'zone-summary');
  summary.append(h('h3', 'zone-name', zone.name));
  const temperatures = h('p', 'zone-temperatures');
  temperatures.append(
    document.createTextNode(`현재 ${zone.currentTemperature ?? '—'}° · 설정 `),
    h('strong', '', `${zone.setpoint ?? '—'}°`)
  );
  summary.append(temperatures);

  const switchLabel = h('label', 'switch');
  const toggle = h('input');
  toggle.type = 'checkbox';
  toggle.checked = Boolean(zone.enabled);
  const requiredPermission = zone.enabled ? 'power_off' : 'power_on';
  toggle.disabled = !controlEnabled || !zone.controls?.includes(requiredPermission);
  toggle.setAttribute('aria-label', `${zone.name} 난방 ${zone.enabled ? '끄기' : '켜기'}`);
  toggle.addEventListener('change', () => setPower(zone, toggle.checked));
  switchLabel.append(toggle, h('span', 'switch-track'));
  const canSet = controlEnabled && zone.controls?.includes('setpoint');
  const controls = h('div', 'zone-controls');
  const down = h('button', 'temperature-step', '−');
  const target = h('div', 'target-temperature', String(zone.setpoint ?? '—'));
  target.append(h('sup', '', '°'));
  const up = h('button', 'temperature-step', '+');
  up.disabled = !canSet;
  down.disabled = !canSet;
  up.setAttribute('aria-label', `${zone.name} 설정온도 1도 올리기`);
  down.setAttribute('aria-label', `${zone.name} 설정온도 1도 내리기`);
  up.addEventListener('click', () => setTemperature(zone, zone.setpoint + 1));
  down.addEventListener('click', () => setTemperature(zone, zone.setpoint - 1));
  controls.append(down, target, up, switchLabel);
  card.append(summary, controls);
  return card;
}

function lightRow(light, connected) {
  const row = h('div', 'light-row');
  const text = h('div', 'light-copy');
  text.append(
    h('strong', '', light.name ?? `전등 ${light.channel}`),
    h('span', light.controls?.length ? 'ready' : 'readonly', light.controls?.length ? '제어 가능' : '상태 확인')
  );

  const switchLabel = h('label', 'switch');
  const toggle = h('input');
  toggle.type = 'checkbox';
  toggle.checked = Boolean(light.enabled);
  const permission = light.enabled ? 'power_off' : 'power_on';
  toggle.disabled = !connected || !light.controls?.includes(permission);
  toggle.setAttribute('aria-label', `${light.name} 전등 ${light.enabled ? '끄기' : '켜기'}`);
  toggle.addEventListener('change', () => setLightPower(light, toggle.checked));
  switchLabel.append(toggle, h('span', 'switch-track'));
  row.append(text, switchLabel);
  return row;
}

function render(status) {
  latestStatus = status;
  lastSequence = status.sequence ?? lastSequence;
  const ew11Connected = Boolean(status.ew11?.connected);
  const officialConnected = Boolean(status.integration?.officialState?.available);
  const connected = officialConnected || ew11Connected;
  const startedAt = Date.parse(status.service?.startedAt ?? '');
  const starting = !connected && Number.isFinite(startedAt) && Date.now() - startedAt < 10000 && !status.ew11?.error;
  connectionElement.className = `connection ${connected ? 'online' : starting ? 'pending' : 'offline'}`;
  connectionElement.replaceChildren(
    h('span'),
    document.createTextNode(
      officialConnected
        ? ' 공식 API 연결됨'
        : ew11Connected
          ? ' EW11 연결됨'
          : starting ? ' 서버 연결 중' : ' 제어 경로 연결 끊김'
    )
  );

  const zones = Object.values(status.heating?.zones ?? {}).sort((a, b) => a.id - b.id);
  if (zones.length) {
    zonesElement.replaceChildren(...zones.map(zone => zoneCard(zone, status.capabilities?.heatingControl)));
    const newest = zones.map(zone => zone.updatedAt).filter(Boolean).sort().at(-1);
    updatedElement.textContent = newest ? `최근 수신 ${new Date(newest).toLocaleTimeString('ko-KR')}` : '수신 중';
    if (!allTemperatureElement.dataset.initialized) {
      setAllTemperature(Math.round(zones.reduce((sum, zone) => sum + zone.setpoint, 0) / zones.length));
      allTemperatureElement.dataset.initialized = 'true';
    }
  }

  const canControl = Boolean(status.capabilities?.heatingControl) && zones.length === 4;
  allOnButton.disabled = !canControl || !zones.every(zone => zone.controls?.includes('power_on'));
  allOffButton.disabled = !canControl || !zones.every(zone => zone.controls?.includes('power_off'));
  applyAllButton.disabled = !canControl || !zones.every(zone => zone.controls?.includes('setpoint'));

  const lights = Object.values(status.lights ?? {}).sort((a, b) => a.channel - b.channel);
  const lightsConnected = status.integration?.featureRoutes?.lights === 'official_api' || ew11Connected;
  if (lights.length) {
    lightsList.replaceChildren(...lights.map(light => lightRow(light, lightsConnected)));
  } else {
    lightsList.replaceChildren(h('p', 'secondary', '전등 상태를 기다리고 있습니다'));
  }
  const batchSwitch = status.batchSwitch ?? {};
  const batchConnected = status.integration?.featureRoutes?.batchSwitch === 'official_api' || ew11Connected;
  batchSwitchToggle.checked = Boolean(batchSwitch.enabled);
  batchSwitchToggle.disabled = !batchConnected || !status.capabilities?.batchSwitchControl;
  batchSwitchState.textContent = batchSwitch.available
    ? `${batchSwitch.enabled ? 'ON' : 'OFF'} · 전등과 환풍기 등 연결 기기를 함께 제어합니다`
    : '전등·환풍기 등 연결 기기 상태를 기다립니다';

  const elevator = status.elevator ?? {};
  const floor = document.querySelector('#elevator-floor');
  const state = document.querySelector('#elevator-state');
  if (elevator.available) {
    floor.textContent = elevator.displayFloor ?? '—';
    state.textContent = elevator.fresh
      ? elevator.doorOpen
        ? '문 열림 · 도착 신호'
        : `${elevator.directionSymbol ?? ''} ${elevator.directionText ?? ''} · 실시간`.trim()
      : '마지막 위치';
  } else {
    floor.textContent = '위치 대기 중';
    state.textContent = '월패드 위치 화면에서 수신을 기다립니다';
  }
  const call = status.elevatorCall ?? {};
  const elevatorCallRoute = status.integration?.featureRoutes?.elevatorCall;
  const elevatorCallConnected = elevatorCallRoute === 'ethernet_command' || ew11Connected;
  const secondsLeft = Math.max(0, (call.availableAtEpoch ?? 0) - Math.floor(Date.now() / 1000));
  elevatorCallButton.disabled = !status.capabilities?.elevatorCall || !elevatorCallConnected || secondsLeft > 0;
  elevatorCallButton.textContent = secondsLeft > 0 ? `다시 호출 ${secondsLeft}초` : '우리 층으로 호출';
  elevatorCallState.textContent = !status.capabilities?.elevatorCall
    ? '호출 경로 확인 중'
    : call.status === 'server_accepted'
      ? '월패드 요청을 서버가 접수했습니다'
      : call.status === 'sent'
      ? '호출 신호 전송됨 · 실제 도착은 확인 필요'
      : elevatorCallConnected
        ? '한 번만 전송하며 자동 재시도하지 않습니다'
        : elevatorCallRoute === 'ethernet_command' ? 'Ethernet 호출 중계기 대기 중' : 'EW11 연결 대기 중';

  const alwaysOn = document.querySelector('#elevator-always-on');
  const b2Call = document.querySelector('#elevator-b2-call');
  const details = status.capabilityDetails ?? {};
  const alwaysOnReady = Boolean(status.capabilities?.elevatorRealtimeWhenWallpadOff);
  const b2Ready = Boolean(status.capabilities?.elevatorB2Call);
  alwaysOn.textContent = alwaysOnReady ? '사용 가능' : '경로 확인 필요';
  alwaysOn.className = alwaysOnReady ? 'ready' : 'pending';
  alwaysOn.title = details.elevatorRealtimeWhenWallpadOff?.reason ?? '';
  b2Call.textContent = b2Ready ? '사용 가능' : '패킷 확인 필요';
  b2Call.className = b2Ready ? 'ready' : 'pending';
  b2Call.title = details.elevatorB2Call?.reason ?? '';
  renderVisitor(status);
  renderCctv(status);

  if (!status.capabilities?.heatingControl) {
    showNotice('현재 앱은 수신 전용입니다. 제어 왕복 검증을 마친 뒤 버튼을 활성화합니다.');
  } else if (noticeElement.textContent.includes('수신 전용')) {
    noticeElement.hidden = true;
  }
}

function renderCctv(status) {
  const cctv = status.cctv ?? {};
  const enabled = Boolean(status.capabilities?.cctvRead && cctv.available);
  if (!enabled) {
    cctvState.textContent = '공식 앱 영상 기능이 제거되어 홈넷 중계 연결을 준비 중입니다';
  } else if (cctvChannel !== null) {
    cctvState.textContent = cctvObjectUrl
      ? `${cctvChannelName} · 홈넷 실시간 영상`
      : `${cctvChannelName} 연결 중`;
  } else {
    cctvState.textContent = '채널을 누르면 홈넷 영상 중계를 시작합니다';
  }
  const channels = Array.isArray(cctv.channels) ? cctv.channels : [];
  cctvChannels.replaceChildren(...channels.map(channel => {
    const button = h('button', `cctv-channel${cctvChannel === channel.id ? ' active' : ''}`, channel.name);
    button.type = 'button';
    button.disabled = !enabled;
    button.addEventListener('click', () => startCctv(channel.id, channel.name));
    return button;
  }));
}

function stopCctv() {
  cctvChannel = null;
  cctvChannelName = '';
  if (cctvTimer) clearTimeout(cctvTimer);
  cctvTimer = null;
  if (cctvObjectUrl) URL.revokeObjectURL(cctvObjectUrl);
  cctvObjectUrl = null;
  cctvPreview.removeAttribute('src');
  cctvPreviewWrap.hidden = true;
  cctvStop.hidden = true;
  if (latestStatus) renderCctv(latestStatus);
}

async function startCctv(channel, name) {
  if (cctvTimer) clearTimeout(cctvTimer);
  cctvTimer = null;
  cctvChannel = channel;
  cctvChannelName = name;
  cctvPreviewWrap.hidden = false;
  cctvStop.hidden = false;
  cctvLoading.hidden = false;
  cctvLoading.textContent = '영상 연결 중';
  cctvState.textContent = `${name} 연결 중`;
  if (latestStatus) renderCctv(latestStatus);
  await loadCctvFrame(name);
}

async function loadCctvFrame(name) {
  if (cctvRequestInFlight) return;
  const channel = cctvChannel;
  if (channel === null) return;
  cctvRequestInFlight = true;
  try {
    const response = await apiFetch(`/api/cctv/channels/${channel}/snapshot`, {cache: 'no-store'});
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.error || 'CCTV 영상을 받을 수 없습니다');
    }
    const nextUrl = URL.createObjectURL(await response.blob());
    if (cctvChannel !== channel) {
      URL.revokeObjectURL(nextUrl);
      return;
    }
    const previousUrl = cctvObjectUrl;
    await new Promise((resolve, reject) => {
      cctvPreview.onload = resolve;
      cctvPreview.onerror = () => reject(new Error('CCTV 이미지 표시 실패'));
      cctvPreview.src = nextUrl;
    });
    cctvPreview.onload = null;
    cctvPreview.onerror = null;
    cctvObjectUrl = nextUrl;
    cctvLoading.hidden = true;
    cctvState.textContent = `${name} · 홈넷 실시간 영상`;
    if (previousUrl) URL.revokeObjectURL(previousUrl);
  } catch (error) {
    if (cctvChannel === channel) {
      cctvLoading.hidden = false;
      cctvLoading.textContent = '영상 연결 대기';
      cctvState.textContent = error.message;
    }
  } finally {
    cctvRequestInFlight = false;
    if (cctvChannel !== null) {
      const delay = cctvChannel === channel ? 700 : 0;
      cctvTimer = setTimeout(() => loadCctvFrame(cctvChannelName), delay);
    }
  }
}

async function postJson(url, body) {
  const response = await apiFetch(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || '명령을 보낼 수 없습니다');
  return result;
}

async function setTemperature(zone, temperature) {
  if (!Number.isFinite(temperature)) return;
  try {
    await postJson(`/api/heating/zones/${zone.id}/setpoint`, {temperature});
    showNotice(`${zone.name} 설정온도를 ${temperature}°C로 요청했습니다. 상태 응답을 기다립니다.`);
  } catch (error) {
    showNotice(error.message);
  }
}

async function setPower(zone, enabled) {
  try {
    await postJson(`/api/heating/zones/${zone.id}/power`, {enabled});
    showNotice(`${zone.name} 난방 ${enabled ? '켜기' : '끄기'}를 요청했습니다.`);
  } catch (error) {
    showNotice(error.message);
    await refresh();
  }
}

async function setLightPower(light, enabled) {
  try {
    await postJson(`/api/lights/${light.channel}/power`, {enabled});
    showNotice(`${light.name} 전등 ${enabled ? '켜기' : '끄기'}를 요청했습니다. 상태 응답을 기다립니다.`);
  } catch (error) {
    showNotice(error.message);
    await refresh();
  }
}

async function setBatchSwitch(enabled) {
  batchSwitchToggle.disabled = true;
  try {
    await postJson('/api/batch-switch/power', {enabled});
    showNotice(`일괄소등 ${enabled ? 'ON' : 'OFF'}을 요청했습니다. 전등과 환풍기 등 연결 기기 상태를 확인합니다.`);
  } catch (error) {
    showNotice(error.message);
    batchSwitchToggle.checked = !enabled;
  } finally {
    await refresh();
  }
}

async function setAllPower(enabled) {
  const zones = Object.values(latestStatus?.heating?.zones ?? {});
  try {
    for (const zone of zones) await postJson(`/api/heating/zones/${zone.id}/power`, {enabled});
    showNotice(`4개 구역 전체 ${enabled ? '켜기' : '끄기'}를 요청했습니다.`);
  } catch (error) {
    showNotice(error.message);
  }
}

async function applyAllTemperature() {
  const zones = Object.values(latestStatus?.heating?.zones ?? {});
  try {
    for (const zone of zones) await postJson(`/api/heating/zones/${zone.id}/setpoint`, {temperature: allTemperature});
    showNotice(`4개 구역 설정온도를 ${allTemperature}°C로 요청했습니다.`);
  } catch (error) {
    showNotice(error.message);
  }
}

async function callElevator() {
  elevatorCallButton.disabled = true;
  try {
    await postJson('/api/elevator/call', {action: 'call'});
    showNotice('엘리베이터 호출 신호를 한 번 보냈습니다. 실제 호출 여부를 확인 중입니다.');
    await refresh();
  } catch (error) {
    showNotice(error.message);
    await refresh();
  }
}

async function unlockVisitorDoor() {
  const eventId = incomingCall.dataset.eventId;
  if (!eventId) return;
  const location = incomingCallLocation.textContent || '현관';
  if (!window.confirm(`${location} 문을 여시겠습니까?\n현재 진행 중인 방문 호출에 한 번만 전송합니다.`)) return;
  const requestId = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  incomingCallUnlock.disabled = true;
  try {
    await postJson(`/api/calls/${encodeURIComponent(eventId)}/unlock`, {action: 'unlock', requestId});
    showNotice('문열림 요청을 한 번 전송했습니다.');
  } catch (error) {
    showNotice(error.message);
  } finally {
    await refresh();
  }
}

async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const response = await apiFetch('/api/status', {cache: 'no-store'});
    if (response.status === 401) throw new Error('인증 토큰이 필요합니다');
    if (!response.ok) throw new Error('Home Agent 응답 오류');
    render(await response.json());
  } catch (error) {
    connectionElement.className = 'connection offline';
    connectionElement.replaceChildren(h('span'), document.createTextNode(` ${error.message || 'Home Agent 접속 불가'}`));
  } finally {
    refreshing = false;
  }
}

async function eventLoop() {
  for (;;) {
    try {
      const response = await apiFetch(`/api/events?after=${lastSequence}`, {cache: 'no-store'});
      if (!response.ok) throw new Error();
      await response.text();
      await refresh();
    } catch {
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  }
}

document.querySelector('#all-temp-down').addEventListener('click', () => setAllTemperature(allTemperature - 1));
document.querySelector('#all-temp-up').addEventListener('click', () => setAllTemperature(allTemperature + 1));
allOnButton.addEventListener('click', () => setAllPower(true));
allOffButton.addEventListener('click', () => setAllPower(false));
applyAllButton.addEventListener('click', applyAllTemperature);
batchSwitchToggle.addEventListener('change', () => setBatchSwitch(batchSwitchToggle.checked));
elevatorCallButton.addEventListener('click', callElevator);
incomingCallDismiss.addEventListener('click', () => {
  dismissedVisitorEventId = incomingCall.dataset.eventId || lastVisitorEventId;
  incomingCall.hidden = true;
});
incomingCallUnlock.addEventListener('click', unlockVisitorDoor);
cctvStop.addEventListener('click', stopCctv);
visitorNotificationButton.addEventListener('click', async () => {
  if (!('Notification' in window)) return;
  const permission = await Notification.requestPermission();
  visitorNotificationButton.hidden = permission === 'granted';
  if (permission === 'granted') showNotice('방문 호출 알림을 허용했습니다. 현재 웹 화면이 실행 중일 때 알림을 표시합니다.');
});

document.querySelectorAll('.bottom-nav button').forEach(button => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.bottom-nav button').forEach(item => item.classList.remove('active'));
    button.classList.add('active');
    const target = button.dataset.view;
    if (target === 'heating') document.querySelector('#climate-title').scrollIntoView({behavior: 'smooth', block: 'start'});
    if (target === 'lights') document.querySelector('#lights-card').scrollIntoView({behavior: 'smooth', block: 'start'});
    if (target === 'elevator') document.querySelector('.elevator-card').scrollIntoView({behavior: 'smooth', block: 'start'});
    if (target === 'visitors') visitorCard.scrollIntoView({behavior: 'smooth', block: 'start'});
    if (target === 'home') window.scrollTo({top: 0, behavior: 'smooth'});
  });
});

if (window.location.hash === '#visitors') {
  requestAnimationFrame(() => visitorCard.scrollIntoView({behavior: 'auto', block: 'start'}));
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
refresh();
eventLoop();
setInterval(refresh, 5000);
