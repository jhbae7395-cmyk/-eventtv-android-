package com.eventtv.messenger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local, bounded background-notification receipts; foreground delegates to JS. */
final class BackgroundMessageReceiptPolicy {
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_RECEIPTS = 2048;
    private final LinkedHashMap<String, Long> receipts = new LinkedHashMap<>();

    synchronized boolean claim(String employeeId, String messageId, long now) {
        if (employeeId == null || messageId == null) return false;
        Iterator<Map.Entry<String, Long>> iterator = receipts.entrySet().iterator();
        while (iterator.hasNext()) {
            long recordedAt = iterator.next().getValue();
            if (now < recordedAt || now - recordedAt >= TTL_MS) iterator.remove();
        }
        String key = employeeId + ":" + messageId;
        if (receipts.containsKey(key)) return false;
        receipts.put(key, now);
        if (receipts.size() > MAX_RECEIPTS) receipts.remove(receipts.keySet().iterator().next());
        return true;
    }
}
