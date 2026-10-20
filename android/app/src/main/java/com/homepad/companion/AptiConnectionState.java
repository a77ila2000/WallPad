package com.homepad.companion;

/** User-facing account state. A saved login alone never means parking is ready. */
final class AptiConnectionState {
    enum State { DISABLED, SIGNED_OUT, UNVERIFIED, READY, RECONNECT, UNAVAILABLE }

    static State resolve(boolean enabled, boolean connected, boolean verified, String error) {
        if (!enabled) return State.DISABLED;
        if (!connected) return State.SIGNED_OUT;
        if (error != null && !error.trim().isEmpty()) {
            return requiresLogin(error) ? State.RECONNECT : State.UNAVAILABLE;
        }
        return verified ? State.READY : State.UNVERIFIED;
    }

    static boolean requiresLogin(String error) {
        return error.contains("만료") || error.contains("로그인이 필요")
                || error.contains("먼저 연결") || error.contains("API 호출 정보가 존재하지 않음")
                || error.contains("로그인 정보를 확인");
    }

    static String title(State state) {
        switch (state) {
            case READY: return "로그인 완료 · 주차 등록 가능";
            case UNVERIFIED: return "로그인 확인됨 · 주차 기능 확인 필요";
            case RECONNECT: return "다시 로그인해 주세요";
            case UNAVAILABLE: return "주차 연결을 확인해 주세요";
            case DISABLED: return "주차 연결이 비활성화되어 있습니다";
            default: return "아파트아이 로그인이 필요합니다";
        }
    }

    static String detail(State state) {
        switch (state) {
            case READY: return "이 앱에서 방문차량 등록과 내역 조회를 사용할 수 있습니다.";
            case UNVERIFIED: return "연결 상태 확인을 누르면 실제 주차 내역을 조회해 확인합니다.";
            case RECONNECT: return "저장된 로그인으로 연결하지 못했습니다. 네이버로 다시 로그인해 주세요.";
            case UNAVAILABLE: return "일시적인 연결 문제일 수 있습니다. 연결 상태 확인을 다시 눌러 주세요.";
            case DISABLED: return "서버의 아파트아이 연결 설정을 확인해 주세요.";
            default: return "아파트아이에 가입할 때 사용한 네이버 계정으로 로그인해 주세요.";
        }
    }
}
