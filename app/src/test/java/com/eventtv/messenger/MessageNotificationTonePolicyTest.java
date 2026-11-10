package com.eventtv.messenger;

import java.util.HashSet;
import java.util.Set;

/** 외부 계정/푸시 없이 java -ea로 실행하는 순수 Java 회귀 테스트. */
public final class MessageNotificationTonePolicyTest {
    private static int assertions;

    private static void check(boolean actual, String description) {
        assertions++;
        if (!actual) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        String[] employees = {null, "", "60001", "60002", "60001 ", "060001"};
        String[] types = {null, "", "messenger_message", "website_event", "approval_event"};
        String[] counts = {null, "", "5", "1", "05", "5.0", "500"};
        for (String local : employees) {
            for (String recipient : employees) {
                for (String type : types) {
                    for (String count : counts) {
                        boolean expected = "60001".equals(local) && "60001".equals(recipient)
                            && "messenger_message".equals(type) && "5".equals(count);
                        check(MessageNotificationTonePolicy.shouldUseFiveToneChannel(local, type, recipient, count)
                            == expected, "strict recipient/type/count policy");
                    }
                }
            }
        }
        check(MessageNotificationTonePolicy.isFiveToneEmployee("60001"), "target employee");
        check(!MessageNotificationTonePolicy.isFiveToneEmployee("60002"), "other employee");

        int[] durations = {-1, 0, 1, 300, 301, 700, 701, 1500};
        String[] expectedVariants = {"off", "off", "short", "short", "default", "default", "long", "long"};
        Set<String> channels = new HashSet<>();
        for (boolean sound : new boolean[]{true, false}) {
            for (int i = 0; i < durations.length; i++) {
                String expected = "eventtv_message_five_" + (sound ? "sound_" : "silent_")
                    + "vib_" + expectedVariants[i] + "_v1";
                String channel = MessageNotificationTonePolicy.getFiveToneChannelId(sound, durations[i]);
                check(expected.equals(channel), "channel variant: " + expected);
                check(!channel.equals("eventtv_messages_v5") && !channel.startsWith("eventtv_vib_"),
                    "must not reuse existing channels");
                channels.add(channel);
            }
        }
        check(channels.size() == 8, "exactly 8 distinct channels");
        MessageNotificationTonePolicy policy = new MessageNotificationTonePolicy();
        check(!policy.claimMessageId(null), "null ID suppressed");
        check(!policy.claimMessageId(""), "empty ID suppressed");
        check(!policy.claimMessageId("   "), "whitespace ID suppressed");
        check(!policy.claimMessageId(new String(new char[257]).replace('\0', 'x')), "oversize ID suppressed");
        check(policy.claimMessageId("42"), "first message");
        check(!policy.claimMessageId("42"), "same message deduplicated");
        check(!policy.claimMessageId(" 42 "), "normalized duplicate suppressed");
        check(policy.claimMessageId("43"), "different message");
        for (int i = 0; i < 512; i++) check(policy.claimMessageId("new-" + i), "bounded recent IDs");
        check(!policy.claimMessageId("new-511"), "newest ID retained");
        check(policy.claimMessageId("42"), "old IDs evicted after finite 512-ID window");
        System.out.println("PASS: " + assertions + " pure Java policy assertions");
    }
}
