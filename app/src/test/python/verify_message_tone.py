#!/usr/bin/env python3
"""Offline 1.4.46 regression: fixed 1.4.44 audio baseline, PCM and optional signed APK.

Run from any directory: python3 app/src/test/python/verify_message_tone.py
Add --apk FILE --previous-apk FILE to verify packaging, version and signing.
No login, push, database writes, channel recreation or audio playback occurs.
"""
import argparse
import array
import hashlib
import io
import math
from pathlib import Path
import re
import subprocess
import sys
import wave
import zipfile

BASELINE = "c9996a9ef3b0e85f2349ddf19ae1b47ac424e303"
RAW = "app/src/main/res/raw/message_five_tones.wav"
OLD_SHA = "0dad82986d19912214fb2adde303a5dd50d0f4ae5d0b8b6b9fbb177974a82160"
NEW_SHA = "9d4e21d1ceccf5a4e2c5804eda96f57da8938721787acb60fe7c0fd4b7142162"
JAVA = "app/src/main/java/com/eventtv/messenger/"
URI = "android.resource://com.eventtv.messenger/raw/message_five_tones"
REPO = Path(__file__).resolve().parents[4]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def baseline(path):
    return subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=REPO)


def pcm(blob):
    with wave.open(io.BytesIO(blob), "rb") as wav:
        params = wav.getparams()
        assert params.nchannels == 1 and params.sampwidth == 2 and params.comptype == "NONE", params
        samples = array.array("h", wav.readframes(params.nframes))
        if sys.byteorder != "little":
            samples.byteswap()
        return params, samples


def verify_audio():
    old_blob, new_blob = baseline(RAW), (REPO / RAW).read_bytes()
    assert sha(old_blob) == OLD_SHA
    assert sha(new_blob) == NEW_SHA
    old_params, old = pcm(old_blob)
    params, new = pcm(new_blob)
    assert params == old_params, (old_params, params)
    assert len(new) == params.nframes
    assert all(y == round(x * 1.5) for x, y in zip(old, new)), "sample-wise round(x * 1.5) mismatch"
    old_peak, peak = max(map(abs, old)), max(map(abs, new))
    clipped = sum(x <= -32768 or x >= 32767 for x in new)
    assert old_peak == 10485 and peak == 15728 and clipped == 0
    old_rms = math.sqrt(sum(x*x for x in old) / len(old))
    rms = math.sqrt(sum(x*x for x in new) / len(new))
    ratio = rms / old_rms
    assert abs(ratio - 1.5) < 0.000001, ratio
    rate = params.framerate
    assert params.nframes / rate == 3.9
    # 10 ms bins span the complete WAV; every tone is a contiguous 300 ms
    # active interval, not several onsets counted while missing extra bursts.
    boundaries = [round(rate * i / 100) for i in range(391)]
    assert boundaries[-1] == len(new)
    active = [max(map(abs, new[left:right])) > 100
              for left, right in zip(boundaries, boundaries[1:])]
    spans = []
    start = None
    for i, flag in enumerate(active + [False]):
        if flag and start is None:
            start = i
        elif not flag and start is not None:
            spans.append((boundaries[start] / rate, boundaries[i] / rate))
            start = None
    expected = [(0, .3), (.9, 1.2), (1.8, 2.1), (2.7, 3), (3.6, 3.9)]
    assert len(spans) == 5, spans
    assert spans == expected, spans
    # Verify every intervening silence sample exactly, not only bin thresholds.
    silence_frames = 0
    for left, right in zip(expected, expected[1:]):
        silence = new[round(left[1] * rate):round(right[0] * rate)]
        assert all(x == 0 for x in silence), "nonzero inter-tone silence"
        silence_frames += len(silence)
    print("PASS fixed baseline:", BASELINE, OLD_SHA)
    print("PASS new WAV SHA-256:", NEW_SHA)
    print("PASS every PCM sample == round(baseline * 1.5); unchanged format:", params)
    print("PASS RMS: %.12f -> %.12f; ratio %.12f; peak %d -> %d; clipped samples %d"
          % (old_rms, rms, ratio, old_peak, peak, clipped))
    print("PASS exactly 5 contiguous active intervals (seconds):", spans)
    print("PASS exactly-zero inter-tone silence: %d frames; duration 3.9 seconds" % silence_frames)
    return new_blob


def verify_source():
    # 1.4.46 intentionally changes lifecycle/health/token handling, not the
    # employee allowlist, sound resource, channel IDs or per-sample gain.
    policy_path = JAVA + "MessageNotificationTonePolicy.java"
    assert (REPO / policy_path).read_bytes() == baseline(policy_path), "employee policy changed"
    main = (REPO / (JAVA + "MainActivity.java")).read_text()
    bridge = (REPO / (JAVA + "AndroidBridge.java")).read_text()
    policy = (REPO / (JAVA + "MessageNotificationTonePolicy.java")).read_text()
    assert 'Uri.parse("android.resource://" + context.getPackageName() + "/raw/message_five_tones")' in main
    assert "Uri fiveToneSoundUri = getFiveToneSoundUri(context);" in main
    sound_lines = [line for line in main.splitlines()
                   if "createMessageChannel(manager, getFiveToneMessageChannelId(true," in line]
    assert len(sound_lines) == 4 and all("true, fiveToneSoundUri, audioAttributes" in line for line in sound_lines)
    assert 'channel.setSound(soundEnabled ? soundUri : null, soundEnabled ? audioAttributes : null);' in main
    assert "deleteNotificationChannel" not in main
    assert 'player.setDataSource(context, MainActivity.getFiveToneSoundUri(context));' in bridge
    assert 'float volume = Math.max(0, Math.min(100, getNotificationVolume())) / 100f;' in bridge
    assert 'player.setVolume(volume, volume);' in bridge
    assert 'new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)' in bridge
    method = bridge.split('public void playMessageNotificationTone(String messageId)')[1].split('private boolean canPlayMessageNotificationTone')[0]
    assert method.count('preparedPlayer.start();') == 1 and 'player.setLooping(false);' in method
    assert 'postDelayed' not in method and '1.5' not in method
    assert '"_v1"' in policy
    print("PASS employee policy byte-identical to 1.4.44; one non-looping WAV, no double gain")
    print("PASS foreground and all 4 audible v1 background channels resolve", URI)
    print("PASS no channel deletion/ID migration; user mute/custom settings, legacy ToneGenerator80 and protections retained")


def verify_apk(current, previous, sdk, source):
    certs = []
    for path in (previous, current):
        output = subprocess.check_output([str(sdk / "apksigner"), "verify", "--print-certs", str(path)], text=True)
        certs.append(re.findall(r"Signer #\d+ certificate SHA-256 digest: (\w+)", output))
    assert certs[0] and certs[0] == certs[1], certs
    print("PASS both APK signatures verified; signer SHA-256:", certs[1][0])
    badging = subprocess.check_output([str(sdk / "aapt2"), "dump", "badging", str(current)], text=True)
    assert "name='com.eventtv.messenger'" in badging.splitlines()[0]
    assert "versionCode='60' versionName='1.4.46'" in badging.splitlines()[0]
    print("PASS", badging.splitlines()[0])
    # AGP optimizeReleaseResources shortens ZIP paths (e.g. res/EQ.wav),
    # while the public raw/message_five_tones name/URI remains unchanged.
    resources = subprocess.check_output([str(sdk / "aapt2"), "dump", "resources", str(current)], text=True)
    resource = re.search(r"resource (0x[0-9a-f]+) raw/message_five_tones\s+\(\) \(file\) (\S+)", resources)
    assert resource, "missing current raw/message_five_tones resource link"
    resource_id, raw_entry = resource.groups()
    with zipfile.ZipFile(previous) as archive:
        old_paths = [name for name in archive.namelist() if name.endswith(".wav")]
        assert len(old_paths) == 1 and sha(archive.read(old_paths[0])) in (OLD_SHA, NEW_SHA)
    with zipfile.ZipFile(current) as archive:
        wav_paths = [name for name in archive.namelist() if name.endswith(".wav")]
        assert wav_paths == [raw_entry], wav_paths
        packaged = archive.read(raw_entry)
        assert packaged == source and sha(packaged) == NEW_SHA
        dex = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
        assert b"android.resource://" in dex and b"/raw/message_five_tones" in dex
        # Channel IDs are assembled dynamically from unchanged v1 components.
        assert b"eventtv_message_five_" in dex and b"_v1" in dex
        assert b"getFiveToneSoundUri" in dex and b"getFiveToneMessageChannelId" in dex
    print("PASS APK actual raw entry byte-exact:", raw_entry, NEW_SHA)
    print("PASS resource table:", resource_id, "raw/message_five_tones ->", raw_entry)
    print("PASS APK resource table + DEX stable URI/channel links resolve current raw WAV")
    print("APK SIZE:", current.stat().st_size, "bytes; APK SHA-256:", sha(current.read_bytes()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--sdk-build-tools", type=Path, default=Path("/home/ubuntu/android-sdk/build-tools/34.0.0"))
    args = parser.parse_args()
    source = verify_audio()
    verify_source()
    if args.apk:
        assert args.previous_apk, "--previous-apk is required with --apk"
        verify_apk(args.apk, args.previous_apk, args.sdk_build_tools, source)


if __name__ == "__main__":
    main()
