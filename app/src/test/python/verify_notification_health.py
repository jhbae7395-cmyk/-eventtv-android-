#!/usr/bin/env python3
"""Offline v1.4.46 contract regression; never logs in, sends FCM, changes a device or DB."""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / 'app/src/main/java/com/eventtv/messenger'
main = (JAVA / 'MainActivity.java').read_text()
bridge = (JAVA / 'AndroidBridge.java').read_text()
service = (JAVA / 'MyFirebaseMessagingService.java').read_text()
policy = (JAVA / 'MessageNotificationTonePolicy.java').read_text()
assert '"60001"' in policy and '"300002".equals(localEmployeeId)' in policy
assert '"360007"' not in policy
assert 'versionCode 60' in (ROOT / 'app/build.gradle').read_text()
assert 'versionName "1.4.46"' in (ROOT / 'app/build.gradle').read_text()
assert 'minSdk 21' in (ROOT / 'app/build.gradle').read_text()
assert 'targetSdk 34' in (ROOT / 'app/build.gradle').read_text()
fields = ['permission', 'soundEnabled', 'appVolume', 'systemVolume', 'systemMaxVolume',
          'ringerMode', 'dnd', 'channelBlocked', 'channelSilent', 'batteryRestricted', 'appVersion']
for field in fields:
    assert f'health.put("{field}",' in bridge, field
for method in ['getNotificationHealth', 'openMessageNotificationSettings', 'openNotificationSoundSettings',
               'openBatterySettings', 'playNotificationTestTone']:
    assert re.search(r'@JavascriptInterface\s+public (?:String|void|boolean) ' + method + r'\(', bridge), method
for forbidden in ['setStreamVolume(', 'setRingerMode(', 'setInterruptionFilter(', 'setBypassDnd(', 'deleteNotificationChannel(']:
    assert forbidden not in main + bridge + service, forbidden
assert 'isPowerSaveMode()' in bridge and 'isBackgroundRestricted()' in bridge
assert 'channel == null || channel.getSound() == null' in bridge
assert 'channel.getImportance() < android.app.NotificationManager.IMPORTANCE_DEFAULT' in bridge
assert 'selectedMessageChannelId()' in bridge
assert 'getFiveToneMessageChannelId(getNotificationSound(), getVibrationDuration())' in bridge
assert 'power.isInteractive()' in main and 'keyguard.isKeyguardLocked()' in main
assert 'private volatile boolean messengerPageReady = false;' in main
assert 'activity.runOnUiThread(() -> {' in main
assert 'JSONObject.quote(token)' in main
assert 'eventtvNativeMessage' in main
assert '!isPositiveNumericId(employeeId) || !isPositiveNumericId(channelId) || !isPositiveNumericId(messageId)' in main
assert '!employeeId.equals(prefs.getString(AndroidBridge.KEY_EMPLOYEE_ID, null))' in main
assert 'prefs.getString(AndroidBridge.KEY_EMPLOYEE_TOKEN, "").isEmpty()' in main
assert 'detail.toString()' in main
assert 'MainActivity.dispatchFcmToken(newToken);' in service
assert 'if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)' in service
assert 'builder.setSound(fiveToneMessage ? MainActivity.getFiveToneSoundUri(this)' in service
assert 'builder.setVibrate(new long[]{0, vibrationDurationMs})' in service
assert service.index('if (MainActivity.isActuallyInForeground(this))') < service.index('manager.notify(')
posting = service[service.index('// Serialize final posting'):service.index('    @Override\n    public void onNewToken')]
assert 'new android.os.Handler(android.os.Looper.getMainLooper()).post(' in posting
assert posting.index('if (MainActivity.isActuallyInForeground(this))') < posting.index('manager.notify(')
badge = bridge[bridge.index('public void setBadgeCount(int count)'):bridge.index('public void updateBadgeFromServer')]
assert badge.index('MainActivity.isActuallyInForeground(context)') < badge.index('nm.notify(')
assert 'messageToneHandler.post(() -> setBadgeCount(count))' in badge
assert 'IN_APP_NOTIFICATION_TONE_THROTTLE_MS = 600L' in bridge
single = bridge[bridge.index('private boolean playSingleNotificationTone'):bridge.index('/** 일반 메시지만 호출한다.')]
assert single.index('if (!canPlayNotificationTone())') < single.index('claimMessageId')
assert single.index('IN_APP_NOTIFICATION_TONE_THROTTLE_MS) return false') < single.index('claimMessageId')
assert 'new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)' in single
assert 'player.setLooping(false)' in bridge
assert 'player.setVolume(volume, volume)' in bridge
assert 'editor.remove(KEY_EMPLOYEE_TOKEN)' in bridge
raw = (ROOT / 'app/src/main/res/raw/message_five_tones.wav').read_bytes()
assert hashlib.sha256(raw).hexdigest() == '9d4e21d1ceccf5a4e2c5804eda96f57da8938721787acb60fe7c0fd4b7142162'
if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        wavs = [n for n in apk.namelist() if n.endswith('.wav')]
        assert len(wavs) == 1 and apk.read(wavs[0]) == raw
        dex = b''.join(apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes\d*\.dex', n))
        for marker in [b'eventtvNativeMessage', b'getNotificationHealth', b'playNotificationTestTone', b'1.4.46']:
            assert marker in dex, marker
print('PASS v1.4.46 native health/events/readiness/settings/token/pre-O/badge contracts; IDs/WAV unchanged')
