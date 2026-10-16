#!/bin/bash
# Builds, tests and installs the Ensomi apps with every output under .build/xcode/: the Debug `dev` cache for tests and
# builds, the optimised `play` cache for the app in /Applications, shared package checkouts and the newest test results.
# Builds that share a cache wait for each other. Runs under macOS /bin/bash 3.2.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
out="$root/.build/xcode"
project="$root/Ensomi.xcodeproj"
installed=/Applications/EnsomiMac.app
rollback_zip="$out/previous-EnsomiMac.zip"
lsregister=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
plistbuddy=/usr/libexec/PlistBuddy
# What the app is built from: an uncommitted change here marks an install modified, a committed one makes it stale.
inputs="Sources Apps Tests project.yml Ensomi.xcodeproj"
keep_results=3
dry=""
# The exact path of each lock this process holds, so that release removes only those.
held_dev=""
held_play=""

usage() {
    cat <<'EOF'
Usage: Tools/mac.sh <command>

  test [xcodebuild args...]  Run the EnsomiMac tests (Debug, dev cache), e.g.
                             -only-testing:EnsomiCoreTests/<class>; TEST_RUNNER_* pass through.
  build dev|ios|cli|release|play
                             dev: EnsomiMac; ios: EnsomiIOS for the iOS simulator;
                             cli: EnsomiACRCloudDebugCLI; release: EnsomiMac Release, unsigned,
                             to check conditional code; play: the optimised app that install copies.
  install                    Build play and replace /Applications/EnsomiMac.app, keeping the previous
                             app as .build/xcode/previous-EnsomiMac.zip. Refuses while Ensomi runs.
                             The app records the commit, with -modified for uncommitted changes.
  rollback                   Put the previous app back from that zip. Refuses while Ensomi runs.
  status                     The installed build against this checkout, other app copies, disk use.
  clean [-n] [--all]         Delete built apps and all but the newest 3 test results; --all deletes
                             .build/xcode except the rollback zip; -n lists without deleting.

Launch only /Applications/EnsomiMac.app, never a build product.
EOF
}

die() { echo "mac.sh: $*" >&2; exit 1; }
need() {
    local tool
    for tool; do command -v "$tool" >/dev/null 2>&1 || die "$tool is not available; this runs on a Mac with Xcode."; done
}
rel() { echo "${1#"$root"/}"; }

# One lock per derived-data cache: a directory holding its owner's PID, broken once that process is gone.
lock() {
    local dir="$out/$1.lock" pid said=""
    case $1 in
        dev) if [ -n "$held_dev" ]; then return; fi ;;
        play) if [ -n "$held_play" ]; then return; fi ;;
        *) die "there is no $1 cache." ;;
    esac
    mkdir -p "$out"
    until mkdir "$dir" 2>/dev/null; do
        pid=$(cat "$dir/pid" 2>/dev/null || true)
        if [ -z "$pid" ]; then
            sleep 1 # the owner may not have written its PID yet
            pid=$(cat "$dir/pid" 2>/dev/null || true)
        fi
        if [ -n "$pid" ] && { kill -0 "$pid" 2>/dev/null || ps -p "$pid" >/dev/null 2>&1; }; then
            if [ -z "$said" ]; then echo "Waiting for the $1 cache: process $pid is using it." >&2; fi
            said=1
            sleep 5
        else
            echo "Removing the stale $1 lock of process ${pid:-unknown}." >&2
            rm -rf "$dir"
        fi
    done
    case $1 in dev) held_dev=$dir ;; play) held_play=$dir ;; esac
    echo "$$" >"$dir/pid"
}
release() {
    if [ -n "$held_dev" ]; then rm -rf "$held_dev"; fi
    if [ -n "$held_play" ]; then rm -rf "$held_play"; fi
}
trap release EXIT
trap 'exit 130' INT TERM

# xcodebuild in cache $1 (dev or play) with the shared package checkouts. The caller holds the cache's lock.
xcb() {
    local cache=$1
    shift
    xcodebuild -project "$project" -derivedDataPath "$out/$cache" -clonedSourcePackagesDirPath "$out/SourcePackages" "$@"
}

# git in the checkout without optional locks: the checkout may be shared with another machine, so git status must not
# refresh its index or take index.lock.
checkout_git() { git --no-optional-locks -C "$root" "$@"; }
# shellcheck disable=SC2086 # $inputs is a list of paths
changes() { checkout_git status --porcelain -- $inputs 2>/dev/null; }
# What the installed app records: the short commit, with -modified when the app's sources have uncommitted changes or
# a local runtime xcframework is set, or "unknown" when git cannot read the checkout. Recording never stops a build.
build_id() {
    local id
    id=$(checkout_git rev-parse --short HEAD 2>/dev/null) || { echo unknown; return; }
    if [ -n "$(changes)" ] || [ -n "${ENSOMI_RUNTIME_XCFRAMEWORK:-}" ]; then id="$id-modified"; fi
    echo "$id"
}
plist_value() { "$plistbuddy" -c "Print :$1" "$installed/Contents/Info.plist" 2>/dev/null || true; }
count_results() {
    local result count=0
    for result in "$out"/results/*.xcresult; do if [ -e "$result" ]; then count=$((count + 1)); fi; done
    echo "$count"
}

remove() {
    case $1 in "$out"/*) ;; *) die "refusing to remove $1, outside .build/xcode." ;; esac
    if [ "$1" = "$rollback_zip" ]; then die "refusing to remove the rollback zip."; fi
    if [ -n "$dry" ]; then echo "Would remove $(rel "$1")"; else rm -rf "$1" && echo "Removed $(rel "$1")"; fi
}
# Xcode registers each built app with LaunchServices, which can then open or prompt for it instead of the installed one.
unregister_and_remove() {
    if [ -z "$dry" ] && [ -x "$lsregister" ]; then "$lsregister" -u "$1" >/dev/null 2>&1 || true; fi
    remove "$1"
}
built_apps() {
    local cache
    for cache in "$out/dev" "$out/play"; do
        if [ -d "$cache" ]; then find "$cache" -type d -name '*.app' -prune; fi
    done
}
prune_results() {
    local result extra
    extra=$(($(count_results) - keep_results))
    # The names are UTC stamps, so the glob lists the oldest first.
    for result in "$out"/results/*.xcresult; do
        if [ "$extra" -le 0 ]; then break; fi
        remove "$result"
        extra=$((extra - 1))
    done
}
refuse_if_running() {
    local status=0
    pgrep -x EnsomiMac >/dev/null 2>&1 || status=$?
    case $status in
        0) die "Ensomi is running. Quit it and run this again; mac.sh never quits it." ;;
        1) ;;
        *) die "pgrep cannot tell whether Ensomi is running (exit $status); quit it and check by hand." ;;
    esac
}
refuse_if_unwritable() {
    if [ ! -w "$(dirname "$installed")" ]; then die "This user cannot write to $(dirname "$installed")."; fi
}

cmd_test() {
    local result status=0
    need xcodebuild
    lock dev
    mkdir -p "$out/results"
    result="$out/results/$(date -u +%Y%m%dT%H%M%SZ).xcresult"
    xcb dev -scheme EnsomiMac -configuration Debug -destination 'platform=macOS' -resultBundlePath "$result" \
        "$@" test || status=$?
    echo "Result bundle: $(rel "$result")"
    prune_results >/dev/null
    return "$status"
}

# The app /Applications holds: the Debug configuration (its signing settings and DEBUG tools) compiled optimised, with
# its build id in its Info.plist.
build_play() {
    local build
    build=$(build_id)
    # Both caches resolve packages into the shared SourcePackages, so a play build also waits for dev builds.
    lock dev
    lock play
    xcb play -scheme EnsomiMac -configuration Debug -destination 'platform=macOS' \
        SWIFT_OPTIMIZATION_LEVEL=-O SWIFT_COMPILATION_MODE=wholemodule ENSOMI_BUILD="$build" build
}

cmd_build() {
    case "$#:${1:-}" in
        1:dev | 1:ios | 1:cli | 1:release | 1:play) ;;
        *) die "build takes one of dev, ios, cli, release, play." ;;
    esac
    need xcodebuild
    case $1 in
        dev) lock dev; xcb dev -scheme EnsomiMac -configuration Debug -destination 'platform=macOS' build ;;
        ios) lock dev; xcb dev -scheme EnsomiIOS -configuration Debug -destination 'generic/platform=iOS Simulator' \
            build CODE_SIGNING_ALLOWED=NO ;;
        cli) lock dev; xcb dev -scheme EnsomiACRCloudDebugCLI -configuration Debug -destination 'platform=macOS' build ;;
        release) lock dev; xcb dev -scheme EnsomiMac -configuration Release -destination 'platform=macOS' \
            build CODE_SIGNING_ALLOWED=NO ;;
        play) build_play; echo "Built for Tools/mac.sh install; do not launch the product." ;;
    esac
}

show_installed() {
    local build
    build=$(plist_value EnsomiBuild)
    echo "Installed: $installed, build ${build:-not recorded}"
}

cmd_install() {
    local product="$out/play/Build/Products/Debug/EnsomiMac.app"
    if [ $# -gt 0 ]; then die "install takes no arguments."; fi
    refuse_if_running # before a build of minutes
    refuse_if_unwritable
    need xcodebuild ditto
    build_play
    if [ ! -d "$product" ]; then die "The build left no $(rel "$product")."; fi
    refuse_if_running # before the rollback zip is replaced
    if [ -d "$installed" ]; then
        ditto -c -k --keepParent "$installed" "$rollback_zip.part"
        mv -f "$rollback_zip.part" "$rollback_zip"
        echo "Kept the previous app as $(rel "$rollback_zip")."
    fi
    refuse_if_running # the zip takes seconds
    rm -rf "$installed"
    ditto "$product" "$installed"
    unregister_and_remove "$product"
    "$lsregister" -f "$installed" || echo "LaunchServices did not register $installed." >&2
    show_installed
}

cmd_rollback() {
    if [ ! -f "$rollback_zip" ]; then die "There is no $(rel "$rollback_zip") to roll back to."; fi
    refuse_if_unwritable
    need ditto
    lock play # waits for an install's build
    refuse_if_running
    rm -rf "$installed"
    ditto -x -k "$rollback_zip" "$(dirname "$installed")"
    "$lsregister" -f "$installed" || echo "LaunchServices did not register $installed." >&2
    show_installed
}

cmd_status() {
    local build entry value
    echo "Checkout: $(checkout_git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)" \
        "$(checkout_git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    if [ ! -d "$installed" ]; then
        echo "STALE: nothing is installed at $installed."
    else
        show_installed
        build=$(plist_value EnsomiBuild)
        # The runtime pin (Package.resolved) and the model pin are among the app's sources.
        # shellcheck disable=SC2086 # $inputs is a list of paths
        if [ -z "$build" ]; then
            echo "STALE: the installed app records no build; Tools/mac.sh install records one."
        elif ! checkout_git diff --quiet "${build%-modified}" HEAD -- $inputs 2>/dev/null; then
            echo "STALE: the app's sources changed since ${build%-modified}, or git cannot compare them."
        fi
        if [ "$build" != "${build%-modified}" ]; then
            echo "MODIFIED: the installed app was built from uncommitted changes or a local runtime at ${build%-modified}."
        fi
    fi

    echo "Other io.ensomi.mac and io.pulsefield.mac app copies (Spotlight, LaunchServices):"
    {
        if command -v mdfind >/dev/null 2>&1; then
            mdfind "kMDItemCFBundleIdentifier == 'io.ensomi.mac' || kMDItemCFBundleIdentifier == 'io.pulsefield.mac'" \
                2>/dev/null || true
        fi
        # A dump record per bundle, between dashed lines: the bundle's path, then its identifier among other fields.
        if [ -x "$lsregister" ]; then
            "$lsregister" -dump 2>/dev/null | awk '
                /^-+$/ { if (hit) print path; path = ""; hit = 0; next }
                $1 == "path:" && path == "" { sub(/^[ \t]*path:[ \t]*/, ""); sub(/ \(0x[0-9a-f]+\)$/, ""); path = $0 }
                $1 == "identifier:" && ($2 == "io.ensomi.mac" || $2 == "io.pulsefield.mac") { hit = 1 }
                END { if (hit) print path }' || true
        fi
    } | sort -u | while IFS= read -r entry; do
        if [ "$entry" = "$installed" ]; then continue; fi
        if [ -e "$entry" ]; then echo "  $entry"; else echo "  $entry (registered, missing)"; fi
    done

    echo "Disk use:"
    for entry in "$out"/* "$root"/.build/* "$HOME"/Library/Developer/Xcode/DerivedData/Ensomi-* \
        "$HOME"/Library/Developer/Xcode/DerivedData/Pulsefield-*; do
        if [ ! -e "$entry" ] || [ "$entry" = "$out" ]; then continue; fi
        value=$(du -sh "$entry" 2>/dev/null | awk '{ print $1 }' || true)
        case $entry in
            "$out"/*) printf '  %6s  %s\n' "$value" "$(rel "$entry")" ;;
            "$root"/*) printf '  %6s  %s (stray, clean leaves it)\n' "$value" "$(rel "$entry")" ;;
            *) printf '  %6s  %s (default DerivedData)\n' "$value" "$entry" ;;
        esac
    done
    echo "Test result bundles: $(count_results)"
}

cmd_clean() {
    local all="" entry
    for entry; do case $entry in -n) dry=1 ;; --all) all=1 ;; *) die "clean takes -n and --all." ;; esac; done
    if [ ! -d "$out" ]; then echo "Nothing to clean."; return; fi
    if [ -z "$dry" ]; then lock dev; lock play; fi
    built_apps | while IFS= read -r entry; do unregister_and_remove "$entry"; done
    prune_results
    if [ -n "$all" ]; then
        for entry in "$out"/*; do
            case $entry in "$rollback_zip" | "$out"/dev.lock | "$out"/play.lock) continue ;; esac
            if [ -e "$entry" ]; then remove "$entry"; fi
        done
    fi
}

command=${1:-help}
if [ $# -gt 0 ]; then shift; fi
case $command in
    test) cmd_test "$@" ;;
    build) cmd_build "$@" ;;
    install) cmd_install "$@" ;;
    rollback) cmd_rollback ;;
    status) cmd_status ;;
    clean) cmd_clean "$@" ;;
    help | -h | --help) usage ;;
    *) usage >&2; exit 2 ;;
esac
