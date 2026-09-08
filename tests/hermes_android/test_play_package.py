import struct
import zipfile

import pytest

from scripts.verify_android_play_package import inspect_elf, inspect_manifest, inspect_payload


def elf(machine=183, alignment=16384):
    payload = bytearray(120)
    payload[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", payload, 18, machine)
    struct.pack_into("<Q", payload, 32, 64)
    struct.pack_into("<HH", payload, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", payload, 64, 1, 5, 0, 0, 0, len(payload), len(payload), alignment)
    return bytes(payload)


def test_manifest_accepts_only_declared_play_surface_and_expected_version():
    xml = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.mobilefork.hermesagent" android:versionName="0.13.156" android:versionCode="145690">
      <uses-sdk android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/>
      <application android:allowBackup="false"><meta-data android:name="com.mobilefork.hermesagent.DISTRIBUTION" android:value="play"/>
      <activity android:name="com.mobilefork.hermesagent.play.PlayActivity" android:exported="true"/></application></manifest>'''
    assert inspect_manifest(xml, version_name="0.13.156", version_code=145690)["edition"] == "play"
    for altered in (
        xml.replace('android:value="play"', 'android:value="full"'),
        xml.replace("android.permission.INTERNET", "android.permission.QUERY_ALL_PACKAGES"),
        xml.replace("</application>", '<service android:name="com.mobilefork.hermesagent.device.HermesAccessibilityService"/></application>'),
        xml.replace('android:targetSdkVersion="36"', 'android:targetSdkVersion="35"'),
        xml.replace("</manifest>", '<uses-permission-sdk-23 android:name="android.permission.QUERY_ALL_PACKAGES"/></manifest>'),
    ):
        with pytest.raises(ValueError):
            inspect_manifest(altered)


def test_native_alignment_exclusions_and_aab_payload_comparison(tmp_path):
    assert inspect_elf(elf(), 183) == [16384]
    with pytest.raises(ValueError, match="16 KiB"):
        inspect_elf(elf(alignment=4096), 183)
    apk, aab = tmp_path / "play.apk", tmp_path / "play.aab"
    entries = {f"lib/{abi}/libhermes_android_llama_server_experimental.so": elf(machine)
               for abi, machine in (("arm64-v8a", 183), ("x86_64", 62))}
    with zipfile.ZipFile(apk, "w") as archive, zipfile.ZipFile(aab, "w") as bundle:
        for name, data in entries.items():
            archive.writestr(name, data)
            bundle.writestr("base/" + name, data)
    assert inspect_payload(apk, aab)["bundle_payload_matched"]
    with zipfile.ZipFile(apk, "a") as archive:
        archive.writestr("assets/hermes-linux/manifest.json", "{}")
    with pytest.raises(ValueError, match="Full-edition"):
        inspect_payload(apk)


def profile_archives(tmp_path, mutation=None):
    apk, aab = tmp_path / "profile.apk", tmp_path / "profile.aab"
    native = {f"lib/{abi}/libhermes_android_llama_server_experimental.so": elf(machine)
              for abi, machine in (("arm64-v8a", 183), ("x86_64", 62))}
    profiles = {"baseline.prof": b"profile bytes", "baseline.profm": b"profile metadata bytes"}
    with zipfile.ZipFile(apk, "w") as archive, zipfile.ZipFile(aab, "w") as bundle:
        for name, data in native.items():
            archive.writestr(name, data)
            bundle.writestr("base/" + name, data)
        for name, data in profiles.items():
            archive.writestr("assets/dexopt/" + name, data)
            if mutation == "missing-profile" and name == "baseline.profm":
                continue
            origin = "BUNDLE-METADATA/com.android.tools.build.profiles/" + name
            bundle.writestr(origin, data + b"changed" if mutation == "changed-profile" else data)
            if mutation == "ambiguous-profile":
                bundle.writestr("base/assets/dexopt/" + name, data)
            if mutation == "duplicate-entry":
                with pytest.warns(UserWarning, match="Duplicate name"):
                    bundle.writestr(origin, data)
        if mutation == "extra-asset":
            bundle.writestr("base/assets/unreviewed-runtime.dat", b"extra")
    return apk, aab


def test_release_profiles_keep_exact_bytes_across_agp_container_locations(tmp_path):
    apk, aab = profile_archives(tmp_path)
    assert inspect_payload(apk, aab)["bundle_payload_matched"]


@pytest.mark.parametrize("mutation", ["changed-profile", "missing-profile", "ambiguous-profile", "extra-asset", "duplicate-entry"])
def test_profile_mapping_cannot_hide_changed_missing_or_extra_payloads(tmp_path, mutation):
    apk, aab = profile_archives(tmp_path, mutation)
    with pytest.raises(ValueError):
        inspect_payload(apk, aab)
