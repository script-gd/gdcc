#!/usr/bin/env bash
set -euo pipefail

ZIG_VERSION="0.16.0"
ZIG_MIRROR_URL="${ZIG_MIRROR_URL:-https://zigmirror.com}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1; pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." >/dev/null 2>&1; pwd)"
DIST_DIR="$REPO_ROOT/build/distributions"
TMP_ROOT="$REPO_ROOT/tmp/full-distribution"
DOWNLOAD_DIR="$REPO_ROOT/tmp/zig-downloads/$ZIG_VERSION"
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
    archive_path="$DOWNLOAD_DIR/$archive_name"
    part_path="$archive_path.part"
    url="$ZIG_MIRROR_URL/$archive_name"

    mkdir -p "$DOWNLOAD_DIR"

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

add_zig_to_distribution() {
    local platform="$1"
    local input_zip="$2"
    local zig_dir="$3"
    local output_zip="${input_zip%.zip}-full.zip"
    local zip_work_dir

    zip_work_dir="$(dirname "$zig_dir")"

    rm -f "$output_zip"
    cp "$input_zip" "$output_zip"

    (
        cd "$zip_work_dir"
        zip -qr "$output_zip" zig
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
        local archive_path
        local zig_dir

        input_zip="$(find_distribution_zip "$platform")"
        archive_path="$(download_zig_archive "$platform")"
        zig_dir="$(extract_zig_archive "$platform" "$archive_path")"
        add_zig_to_distribution "$platform" "$input_zip" "$zig_dir"
    done
}

main "$@"
