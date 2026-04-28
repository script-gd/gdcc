#!/usr/bin/env bash
set -euo pipefail

ZIG_VERSION="0.16.0"
TEMURIN_FEATURE_VERSION="25"
ZIG_MIRROR_URL="${ZIG_MIRROR_URL:-https://zigmirror.com}"
ADOPTIUM_API_URL="${ADOPTIUM_API_URL:-https://api.adoptium.net/v3/binary/latest}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1; pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1; pwd)"
DIST_DIR="$REPO_ROOT/build/distributions"
TMP_ROOT="$REPO_ROOT/tmp/full-distribution"
ZIG_DOWNLOAD_DIR="$REPO_ROOT/tmp/zig-downloads/$ZIG_VERSION"
JRE_DOWNLOAD_DIR="$REPO_ROOT/tmp/temurin-jre-downloads/$TEMURIN_FEATURE_VERSION"
EXTRACT_ROOT="$TMP_ROOT/extract"
ZIP_WORK_ROOT="$TMP_ROOT/zip-work"

PLATFORMS=(
    "linux-x86_64"
    "windows-x86_64"
    "linux-aarch64"
)

require_tool() {
    local tool="$1"

    if command -v "$tool" >/dev/null 2>&1; then
        return 0
    fi

    printf 'error: required tool not found: %s\n' "$tool" >&2
    exit 1
}

zig_archive_name() {
    local platform="$1"

    case "$platform" in
        linux-x86_64)
            printf 'zig-x86_64-linux-%s.tar.xz\n' "$ZIG_VERSION"
            ;;
        windows-x86_64)
            printf 'zig-x86_64-windows-%s.zip\n' "$ZIG_VERSION"
            ;;
        linux-aarch64)
            printf 'zig-aarch64-linux-%s.tar.xz\n' "$ZIG_VERSION"
            ;;
        *)
            printf 'error: unsupported platform: %s\n' "$platform" >&2
            exit 1
            ;;
    esac
}

zig_executable_name() {
    local platform="$1"

    case "$platform" in
        windows-*)
            printf 'zig.exe\n'
            ;;
        *)
            printf 'zig\n'
            ;;
    esac
}

temurin_os() {
    local platform="$1"

    case "$platform" in
        linux-*)
            printf 'linux\n'
            ;;
        windows-*)
            printf 'windows\n'
            ;;
        *)
            printf 'error: unsupported platform: %s\n' "$platform" >&2
            exit 1
            ;;
    esac
}

temurin_arch() {
    local platform="$1"

    case "$platform" in
        linux-x86_64 | windows-x86_64)
            printf 'x64\n'
            ;;
        linux-aarch64)
            printf 'aarch64\n'
            ;;
        *)
            printf 'error: unsupported platform: %s\n' "$platform" >&2
            exit 1
            ;;
    esac
}

jre_archive_name() {
    local platform="$1"

    case "$platform" in
        linux-*)
            printf 'temurin-%s-jre-%s.tar.gz\n' "$TEMURIN_FEATURE_VERSION" "$platform"
            ;;
        windows-*)
            printf 'temurin-%s-jre-%s.zip\n' "$TEMURIN_FEATURE_VERSION" "$platform"
            ;;
        *)
            printf 'error: unsupported platform: %s\n' "$platform" >&2
            exit 1
            ;;
    esac
}

java_executable_name() {
    local platform="$1"

    case "$platform" in
        windows-*)
            printf 'java.exe\n'
            ;;
        *)
            printf 'java\n'
            ;;
    esac
}

find_distribution_zip() {
    local platform="$1"
    local -a matches=()

    if [[ ! -d "$DIST_DIR" ]]; then
        printf 'error: distribution directory does not exist: %s\n' "$DIST_DIR" >&2
        exit 1
    fi

    while IFS= read -r path; do
        matches+=("$path")
    done < <(find "$DIST_DIR" -maxdepth 1 -type f -name "gdcc-*-$platform.zip" ! -name "*-full.zip" | sort)

    if [[ "${#matches[@]}" -eq 0 ]]; then
        printf 'error: no distribution zip found for platform %s in %s\n' "$platform" "$DIST_DIR" >&2
        exit 1
    fi

    if [[ "${#matches[@]}" -gt 1 ]]; then
        printf 'error: multiple distribution zips found for platform %s:\n' "$platform" >&2
        printf '  %s\n' "${matches[@]}" >&2
        exit 1
    fi

    printf '%s\n' "${matches[0]}"
}

download_zig_archive() {
    local platform="$1"
    local archive_name
    local archive_path
    local part_path
    local url

    archive_name="$(zig_archive_name "$platform")"
    archive_path="$ZIG_DOWNLOAD_DIR/$archive_name"
    part_path="$archive_path.part"
    url="$ZIG_MIRROR_URL/$archive_name"

    mkdir -p "$ZIG_DOWNLOAD_DIR"

    if [[ -s "$archive_path" ]]; then
        printf 'Using cached Zig archive: %s\n' "$archive_path" >&2
        printf '%s\n' "$archive_path"
        return 0
    fi

    printf 'Downloading %s\n' "$url" >&2
    rm -f "$part_path"
    curl -fL --retry 3 --connect-timeout 20 --output "$part_path" "$url"
    mv "$part_path" "$archive_path"

    printf '%s\n' "$archive_path"
}

download_jre_archive() {
    local platform="$1"
    local os
    local arch
    local archive_name
    local archive_path
    local part_path
    local url

    os="$(temurin_os "$platform")"
    arch="$(temurin_arch "$platform")"
    archive_name="$(jre_archive_name "$platform")"
    archive_path="$JRE_DOWNLOAD_DIR/$archive_name"
    part_path="$archive_path.part"
    url="$ADOPTIUM_API_URL/$TEMURIN_FEATURE_VERSION/ga/$os/$arch/jre/hotspot/normal/eclipse"

    mkdir -p "$JRE_DOWNLOAD_DIR"

    if [[ -s "$archive_path" ]]; then
        printf 'Using cached Temurin JRE archive: %s\n' "$archive_path" >&2
        printf '%s\n' "$archive_path"
        return 0
    fi

    printf 'Downloading %s\n' "$url" >&2
    rm -f "$part_path"
    curl -fL --retry 3 --connect-timeout 20 --output "$part_path" "$url"
    mv "$part_path" "$archive_path"

    printf '%s\n' "$archive_path"
}

extract_zig_archive() {
    local platform="$1"
    local archive_path="$2"
    local extract_dir="$EXTRACT_ROOT/$platform"
    local zig_dir="$ZIP_WORK_ROOT/$platform/zig"
    local executable_name
    local -a roots=()

    executable_name="$(zig_executable_name "$platform")"

    rm -rf "$extract_dir"
    rm -rf "$zig_dir"
    mkdir -p "$extract_dir"
    mkdir -p "$zig_dir"

    case "$archive_path" in
        *.zip)
            unzip -q "$archive_path" -d "$extract_dir"
            ;;
        *.tar.xz)
            tar -xJf "$archive_path" -C "$extract_dir"
            ;;
        *)
            printf 'error: unsupported Zig archive format: %s\n' "$archive_path" >&2
            exit 1
            ;;
    esac

    while IFS= read -r path; do
        roots+=("$path")
    done < <(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d | sort)

    if [[ "${#roots[@]}" -ne 1 ]]; then
        printf 'error: expected exactly one top-level directory in Zig archive for %s, found %s\n' "$platform" "${#roots[@]}" >&2
        exit 1
    fi

    cp -R "${roots[0]}"/. "$zig_dir/"

    if [[ ! -f "$zig_dir/$executable_name" ]]; then
        printf 'error: expected Zig executable missing after extraction: %s\n' "$zig_dir/$executable_name" >&2
        exit 1
    fi

    chmod +x "$zig_dir/$executable_name"
    printf '%s\n' "$zig_dir"
}

extract_jre_archive() {
    local platform="$1"
    local archive_path="$2"
    local extract_dir="$EXTRACT_ROOT/$platform-jre"
    local jre_dir="$ZIP_WORK_ROOT/$platform/jre"
    local executable_name
    local -a roots=()

    executable_name="$(java_executable_name "$platform")"

    rm -rf "$extract_dir"
    rm -rf "$jre_dir"
    mkdir -p "$extract_dir"
    mkdir -p "$jre_dir"

    case "$archive_path" in
        *.zip)
            unzip -q "$archive_path" -d "$extract_dir"
            ;;
        *.tar.gz)
            tar -xzf "$archive_path" -C "$extract_dir"
            ;;
        *)
            printf 'error: unsupported Temurin JRE archive format: %s\n' "$archive_path" >&2
            exit 1
            ;;
    esac

    while IFS= read -r path; do
        roots+=("$path")
    done < <(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d | sort)

    if [[ "${#roots[@]}" -ne 1 ]]; then
        printf 'error: expected exactly one top-level directory in Temurin JRE archive for %s, found %s\n' "$platform" "${#roots[@]}" >&2
        exit 1
    fi

    cp -R "${roots[0]}"/. "$jre_dir/"

    if [[ ! -f "$jre_dir/bin/$executable_name" ]]; then
        printf 'error: expected Java executable missing after extraction: %s\n' "$jre_dir/bin/$executable_name" >&2
        exit 1
    fi

    chmod +x "$jre_dir/bin/$executable_name"
    printf '%s\n' "$jre_dir"
}

write_full_distribution() {
    local input_zip="$1"
    local content_root="$2"
    local output_zip="${input_zip%.zip}-full.zip"

    rm -f "$output_zip"
    cp "$input_zip" "$output_zip"

    (
        cd "$content_root"
        zip -qr "$output_zip" zig jre
    )

    printf 'Wrote %s\n' "$output_zip"
}

main() {
    require_tool curl
    require_tool find
    require_tool sort
    require_tool tar
    require_tool unzip
    require_tool zip

    mkdir -p "$TMP_ROOT"
    mkdir -p "$ZIP_WORK_ROOT"

    for platform in "${PLATFORMS[@]}"; do
        printf 'Packaging full distribution for %s\n' "$platform"

        local input_zip
        local zig_archive_path
        local jre_archive_path
        local zig_dir
        local jre_dir
        local content_root

        input_zip="$(find_distribution_zip "$platform")"
        zig_archive_path="$(download_zig_archive "$platform")"
        jre_archive_path="$(download_jre_archive "$platform")"
        zig_dir="$(extract_zig_archive "$platform" "$zig_archive_path")"
        jre_dir="$(extract_jre_archive "$platform" "$jre_archive_path")"
        content_root="$(dirname "$zig_dir")"

        if [[ "$(dirname "$jre_dir")" != "$content_root" ]]; then
            printf 'error: internal package work directories differ for platform %s\n' "$platform" >&2
            exit 1
        fi

        write_full_distribution "$input_zip" "$content_root"
    done
}

main "$@"
