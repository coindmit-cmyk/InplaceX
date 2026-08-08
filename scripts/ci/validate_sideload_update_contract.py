#!/usr/bin/env python3
"""Fail-closed static guard for the signed-candidate sideload updater boundary."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "InplaceX-android" / "app"
CANDIDATE = APP / "src" / "signedReleaseCandidate"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_merged_manifest(path: Path, updater_enabled: bool) -> None:
    root = ET.parse(path).getroot()
    permissions = [
        node.get(ANDROID_NS + "name")
        for node in root.findall("uses-permission")
    ]
    providers = [
        node
        for node in root.findall("application/provider")
        if node.get(ANDROID_NS + "authorities", "").endswith(".update-files")
    ]
    if updater_enabled:
        require(
            permissions.count("android.permission.REQUEST_INSTALL_PACKAGES") == 1,
            "enabled merged manifest lacks the exact installer permission",
        )
        require(len(providers) == 1, "enabled merged manifest lacks the exact update provider")
        provider = providers[0]
        require(
            provider.get(ANDROID_NS + "name") == "androidx.core.content.FileProvider",
            "merged update provider class drift",
        )
        require(provider.get(ANDROID_NS + "exported") == "false", "merged update provider is exported")
        require(
            provider.get(ANDROID_NS + "grantUriPermissions") == "true",
            "merged update provider lacks explicit URI grants",
        )
        path_metadata = [
            node
            for node in provider.findall("meta-data")
            if node.get(ANDROID_NS + "name") == "android.support.FILE_PROVIDER_PATHS"
        ]
        require(len(path_metadata) == 1, "merged update provider path metadata drift")
        require(
            path_metadata[0].get(ANDROID_NS + "resource") == "@xml/update_file_paths",
            "merged update provider path resource drift",
        )
    else:
        require(
            "android.permission.REQUEST_INSTALL_PACKAGES" not in permissions,
            "installer permission escaped into a disabled merged manifest",
        )
        require(not providers, "update FileProvider escaped into a disabled merged manifest")


def validate(merged_manifest: Optional[Path] = None, updater_enabled: bool = False) -> None:
    candidate_manifest = CANDIDATE / "AndroidManifest.xml"
    candidate_paths = CANDIDATE / "res" / "xml" / "update_file_paths.xml"
    require(candidate_manifest.is_file(), "signed candidate update manifest is missing")
    require(candidate_paths.is_file(), "signed candidate FileProvider paths are missing")

    permission_manifests: list[Path] = []
    provider_manifests: list[Path] = []
    for manifest in APP.glob("src/*/AndroidManifest.xml"):
        source = manifest.read_text(encoding="utf-8")
        if "android.permission.REQUEST_INSTALL_PACKAGES" in source:
            permission_manifests.append(manifest)
        if "androidx.core.content.FileProvider" in source:
            provider_manifests.append(manifest)
    require(permission_manifests == [candidate_manifest], "installer permission escaped signedReleaseCandidate")
    require(provider_manifests == [candidate_manifest], "update FileProvider escaped signedReleaseCandidate")

    manifest_root = ET.parse(candidate_manifest).getroot()
    permissions = [node.get(ANDROID_NS + "name") for node in manifest_root.findall("uses-permission")]
    require(permissions == ["android.permission.REQUEST_INSTALL_PACKAGES"], "candidate permission set is not exact")
    providers = manifest_root.findall("application/provider")
    require(len(providers) == 1, "candidate must declare exactly one update provider")
    provider = providers[0]
    require(provider.get(ANDROID_NS + "name") == "androidx.core.content.FileProvider", "unexpected provider class")
    require(provider.get(ANDROID_NS + "exported") == "false", "update provider must not be exported")
    require(provider.get(ANDROID_NS + "grantUriPermissions") == "true", "URI grants must be explicit")
    require(provider.get(ANDROID_NS + "authorities") == "${applicationId}.update-files", "provider authority drift")

    paths_root = ET.parse(candidate_paths).getroot()
    children = list(paths_root)
    require(len(children) == 1 and children[0].tag == "cache-path", "FileProvider path must be one cache-path")
    require(children[0].get("name") == "verified_app_updates", "FileProvider path name drift")
    require(children[0].get("path") == "app-updates/", "FileProvider path is broader than app-updates")

    gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    field = 'buildConfigField("boolean", "SIDELOAD_UPDATES_ENABLED", '
    require(gradle.count(field + '"false")') == 2, "debug/release update gates must both be false")
    require(gradle.count(field + '"true")') == 1, "only signedReleaseCandidate may enable updates")

    factory = (APP / "src/main/java/com/mirkori/inplacex/platform/update/AndroidAppUpdateArtifacts.kt").read_text(
        encoding="utf-8",
    )
    require("if (!BuildConfig.SIDELOAD_UPDATES_ENABLED) return null" in factory, "runtime build gate is missing")
    require(
        factory.count("followRedirects = false") == 1,
        "APK client lacks the top-level Ktor redirect gate",
    )
    require(
        "private fun createAppUpdateDownloadClient(): HttpClient = HttpClient(OkHttp) {\n"
        "    applyAppUpdateDownloadClientPolicy()" in factory,
        "production APK client does not apply the top-level redirect policy",
    )
    require(
        factory.count("followRedirects(false)") == 1,
        "APK client lacks the OkHttp engine redirect gate",
    )
    require("followSslRedirects(false)" in factory, "APK SSL redirects are not disabled")
    require("MirkoriPlatformRuntime" not in factory, "public updater depends on authenticated runtime")

    platform_transport = (
        APP / "src/main/java/com/mirkori/inplacex/platform/mirkori/KtorMirkoriPlatformTransport.kt"
    ).read_text(encoding="utf-8")
    require(
        platform_transport.count("followRedirects = false") == 1,
        "Platform client lacks the top-level Ktor redirect gate",
    )
    require(
        "HttpClient(OkHttp) {\n        applyMirkoriHttpClientPolicy(policy)" in platform_transport,
        "production Platform client does not apply the top-level redirect policy",
    )
    require(
        platform_transport.count("followRedirects(false)") == 1,
        "Platform client lacks the OkHttp engine redirect gate",
    )

    activity = (APP / "src/main/java/com/mirkori/inplacex/MainActivity.kt").read_text(encoding="utf-8")
    require("AndroidAppUpdateFactory.createOrNull" in activity, "activity does not compose the public updater")
    require("openUpdateDownload" not in activity, "legacy browser download path remains reachable")
    require("runtime.performInstallAction { claim ->" in activity, "installer launch bypasses runtime hand-off")
    require("runtime.claimInstallAction" not in activity, "split installer claim API remains reachable")
    require("runtime.completeInstallClaim" not in activity, "split installer completion API remains reachable")
    require(
        "withContext(Dispatchers.Main.immediate)" in activity,
        "runtime-owned hand-off does not marshal the Android launch callback",
    )
    require("installer.installIntent(state.artifact)" not in activity, "activity launches a stale UI artifact directly")
    require(
        "initialAppUpdateCompositionState" in activity and "AppUpdateState.PolicyRecovery" in activity,
        "enabled updater construction failure is not blocking",
    )

    runtime = (APP / "src/main/java/com/mirkori/inplacex/platform/update/AppUpdateRuntime.kt").read_text(
        encoding="utf-8",
    )
    require("AppUpdateState.Refreshing" in runtime, "ready updater state remains actionable during refresh")
    require("AppUpdatePolicyReadResult.Invalid" in runtime, "invalid durable policy is not fail-closed")
    require("suspend fun performInstallAction(" in runtime, "runtime-owned installer hand-off is missing")
    require("launch(claim)" in runtime, "installer callback is outside the serialized runtime hand-off")
    require(
        "prepareAuthoritativeCacheTransition" in runtime and "policyRecoveryOnFailure = true" in runtime,
        "authoritative cleanup failure is not fail-closed",
    )
    require(
        "if (!snapshot.recoveryRequired) {\n"
        "            val guarded = before.copy(" in runtime,
        "durable refresh tombstone is conditional on a cached artifact",
    )
    require(
        runtime.index("val guarded = before.copy(") < runtime.index("catalog.check(installedVersionCode)"),
        "durable refresh tombstone is not written before the authoritative request",
    )
    require(
        "val markerPersisted = persist(recoveryMarker)" in runtime
        and "val cacheCleared = clearCacheSafely(policyRecoveryOnFailure = true)" in runtime
        and "return markerPersisted && cacheCleared" in runtime,
        "marker failure skips the independent physical cache invalidation",
    )

    cache = factory
    require("deleteEntryNoFollow" in cache, "stale cache cleanup follows or preserves unsafe entries")
    require("publishAtomicArtifact" in cache, "atomic APK publication cleanup guard is missing")
    require(
        "override fun clear() {\n        val root = ensurePrivateDirectory()" in cache,
        "cache-root recovery errors are swallowed during cleanup",
    )

    if merged_manifest is not None:
        require(merged_manifest.is_file(), f"merged manifest is missing: {merged_manifest}")
        validate_merged_manifest(merged_manifest, updater_enabled)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--merged-manifest", type=Path)
    parser.add_argument("--updater-enabled", action="store_true")
    args = parser.parse_args()
    try:
        require(not args.updater_enabled or args.merged_manifest is not None, "enabled check requires a merged manifest")
        validate(args.merged_manifest, args.updater_enabled)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OK: signedReleaseCandidate sideload isolation contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
