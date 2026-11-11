package com.eventtv.messenger;

/** Offline receipts only: no Android/FCM/login/network. */
public final class BackgroundMessageReceiptPolicyTest {
    private static int count;
    private static void check(boolean condition) {
        count++;
        if (!condition) throw new AssertionError("receipt assertion " + count);
    }
    public static void main(String[] args) {
        BackgroundMessageReceiptPolicy policy = new BackgroundMessageReceiptPolicy();
        check(!policy.claim(null, "1", 0));
        check(!policy.claim("60001", null, 0));
        check(policy.claim("60001", "1", 0));
        check(!policy.claim("60001", "1", 1));
        check(policy.claim("300002", "1", 1));
        check(policy.claim("60001", "2", 2));
        check(!policy.claim("60001", "1", 599999));
        check(policy.claim("60001", "1", 600000));
        BackgroundMessageReceiptPolicy bounded = new BackgroundMessageReceiptPolicy();
        for (int i = 0; i < 2049; i++) check(bounded.claim("60001", String.valueOf(i), 0));
        check(!bounded.claim("60001", "2048", 1));
        check(bounded.claim("60001", "0", 1));
        check(bounded.claim("60001", "2048", 600000));
        check(bounded.claim("60001", "2048", 0)); // monotonic clock reset invalidates future receipts
        System.out.println("PASS: " + count + " bounded background receipt assertions");
    }
}
