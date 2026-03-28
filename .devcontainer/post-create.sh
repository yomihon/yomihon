#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_properties="${repo_root}/local.properties"
user_gradle_properties="${HOME}/.gradle/gradle.properties"
sdk_dir="/opt/android-sdk"

mkdir -p "${repo_root}/.gradle-cache" "${repo_root}/.android-adb" "${HOME}/.android"
touch "${HOME}/.android/repositories.cfg"

if [[ -f "${local_properties}" ]]; then
    tmp_file="$(mktemp)"
    awk -v sdk_dir="${sdk_dir}" '
        BEGIN { updated = 0 }
        /^sdk\.dir=/ {
            print "sdk.dir=" sdk_dir
            updated = 1
            next
        }
        { print }
        END {
            if (!updated) {
                print "sdk.dir=" sdk_dir
            }
        }
    ' "${local_properties}" > "${tmp_file}"
    mv "${tmp_file}" "${local_properties}"
else
    printf 'sdk.dir=%s\n' "${sdk_dir}" > "${local_properties}"
fi

mkdir -p "$(dirname "${user_gradle_properties}")"

tmp_file="$(mktemp)"
if [[ -f "${user_gradle_properties}" ]]; then
    awk '
        BEGIN { skip = 0 }
        /^# codex-devcontainer-start$/ { skip = 1; next }
        /^# codex-devcontainer-end$/ { skip = 0; next }
        skip == 0 { print }
    ' "${user_gradle_properties}" > "${tmp_file}"
else
    : > "${tmp_file}"
fi

cat >> "${tmp_file}" <<'EOF'
# codex-devcontainer-start
# Container-local Gradle overrides for Docker Desktop stability.
org.gradle.parallel=false
org.gradle.vfs.watch=false
org.gradle.workers.max=2
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -XX:+UseG1GC -XX:MaxGCPauseMillis=200
kotlin.daemon.jvmargs=-Xmx1024m -XX:+UseG1GC -XX:MaxMetaspaceSize=384m
# codex-devcontainer-end
EOF

mv "${tmp_file}" "${user_gradle_properties}"
