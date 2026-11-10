package com.eventtv.messenger;

import java.util.LinkedHashSet;
import java.util.Set;

/** 직원별 메시지 수신음 정책. Android 의존성 없이 대상/채널/중복 정책을 검증한다. */
final class MessageNotificationTonePolicy {
    static final String FIVE_TONE_EMPLOYEE_ID = "60001";
    static final String FIVE_TONE_CHANNEL_PREFIX = "eventtv_message_five_";
    private static final int MAX_RECENT_MESSAGE_IDS = 512;
    private final Set<String> recentMessageIds = new LinkedHashSet<>();

    static boolean isFiveToneEmployee(String localEmployeeId) {
        return FIVE_TONE_EMPLOYEE_ID.equals(localEmployeeId);
    }

    static boolean shouldUseFiveToneChannel(
        String localEmployeeId, String type, String payloadEmployeeId, String toneRepeatCount
    ) {
        // count 단독으로는 절대 맞춤 채널을 선택하지 않는다. 이벤트/다른 직원은 기존 채널이다.
        return isFiveToneEmployee(localEmployeeId)
            && "messenger_message".equals(type)
            && FIVE_TONE_EMPLOYEE_ID.equals(payloadEmployeeId)
            && "5".equals(toneRepeatCount);
    }

    static String getFiveToneChannelId(boolean soundEnabled, int vibrationDurationMs) {
        String vibration = vibrationDurationMs <= 0 ? "off"
            : vibrationDurationMs <= 300 ? "short"
            : vibrationDurationMs <= 700 ? "default" : "long";
        return FIVE_TONE_CHANNEL_PREFIX + (soundEnabled ? "sound_" : "silent_")
            + "vib_" + vibration + "_v1";
    }

    /** 재연결/소켓 재전송은 같은 messageId로 수신음을 다시 만들지 않는다. 메모리는 유한하다. */
    synchronized boolean claimMessageId(String messageId) {
        if (messageId == null || messageId.trim().isEmpty() || messageId.length() > 256) return false;
        String normalized = messageId.trim();
        if (!recentMessageIds.add(normalized)) return false;
        if (recentMessageIds.size() > MAX_RECENT_MESSAGE_IDS) {
            recentMessageIds.remove(recentMessageIds.iterator().next());
        }
        return true;
    }
}
