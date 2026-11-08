#!/usr/bin/env bash
# C++ formatting and static analysis. Requires clang-format, clang-tidy and
# the native dependencies documented in docs/BUILD.md.
#   scripts/lint.sh          # check
#   scripts/lint.sh --fix    # reformat, then check
set -euo pipefail

cd "$(dirname "$0")/.."

fix=0
[[ "${1:-}" == "--fix" ]] && fix=1

# Active native sources and tests only. The dormant legacy desktop_test client
# is not part of the current protocol or build.
mapfile -t cpp_files < <(find \
    src \
    client/gtk \
    tests \
    -type f \( -name '*.cpp' -o -name '*.hpp' -o -name '*.h' \) | sort)

# --- clang-format ----------------------------------------------------------
if [[ "$fix" == 1 ]]; then
    echo ">> clang-format --fix (${#cpp_files[@]} files)"
    clang-format -i "${cpp_files[@]}"
else
    echo ">> clang-format --check (${#cpp_files[@]} files)"
    clang-format --dry-run --Werror "${cpp_files[@]}"
fi

# --- clang-tidy ------------------------------------------------------------
# A compile_commands.json with both optional sources enabled gives clang-tidy
# the widest coverage (portal + mutter capture backends).
build_dir=build-lint
if [[ ! -f "$build_dir/compile_commands.json" ]]; then
    echo ">> meson setup $build_dir (portal + mutter enabled)"
    meson setup "$build_dir" -Dportal=enabled -Dmutter=enabled >/dev/null
fi

mapfile -t tidy_files < <(find \
    src/streamer \
    client/gtk \
    -type f -name '*.cpp' | sort)

echo ">> clang-tidy (${#tidy_files[@]} translation units)"
clang-tidy --quiet -p "$build_dir" "${tidy_files[@]}"

echo "lint OK"
