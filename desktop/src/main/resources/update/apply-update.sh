#!/bin/sh
# Lucense's update helper (Linux).
#
# The running app can't replace its own jars, so it stages the new files,
# writes this script and a plan next to them, starts the script and exits.
# This waits for the app to be gone, backs up every file it is about to
# replace or delete, puts the new files in place, checks every one of them
# against the SHA-256 in the plan, and starts Lucense again. If anything at
# all goes wrong it puts every old file back from the backup, so the install
# is either all old or all new, never half of each.
#
#   apply-update.sh MODE PID ROOT STAGING VERSION PLAN RESULT LAUNCHER [ELEVATOR] [UID] [GID]
#
#   MODE      run           do the swap as this user, then start Lucense
#             run-elevated  do the swap through ELEVATOR (pkexec), then start Lucense as this user
#             swap          (under pkexec) the swap only; UID/GID get the files it made back
#   PID       the app's process id, waited for
#   ROOT      the install root (holds bin/Lucense)
#   STAGING   the staging folder (holds files/, gets backup/)
#   VERSION   the version being installed, for result.json
#   PLAN      one line per file: path<TAB>sha256<TAB>exec(0|1); per old jar: stale<TAB>path
#   RESULT    where result.json goes
#   LAUNCHER  the launcher, relative to ROOT (bin/Lucense)
#
# File names are only ever read from the plan into variables and used quoted,
# never pasted into a command line, so a name can't turn into a command.
set -eu
umask 022

MODE=${1:-}
APP_PID=${2:-}
ROOT=${3:-}
STAGING=${4:-}
VERSION=${5:-}
PLAN=${6:-}
RESULT=${7:-}
LAUNCHER=${8:-}
ELEVATOR=${9:--}
OWNER_UID=${10:--}
OWNER_GID=${11:--}

TAB=$(printf '\t')

say() { printf 'apply-update: %s\n' "$*" >&2; }

# Wrong arguments mean the app and this script disagree; nothing is touched.
refuse() { say "refused: $1"; exit 2; }

is_absolute() {
    case $1 in /*) ;; *) return 1 ;; esac
    case "$1/" in */../*|*/./*) return 1 ;; esac
    return 0
}

# A path from the plan: relative, forward slashes, no "." or ".." part, no
# backslash and no control character. It can only name something inside ROOT.
is_safe_relative() {
    case $1 in ''|/*|*/|*//*|*\\*) return 1 ;; esac
    case "/$1/" in */../*|*/./*) return 1 ;; esac
    if printf '%s' "$1" | LC_ALL=C grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

is_hex64() {
    [ ${#1} -eq 64 ] || return 1
    case $1 in *[!0-9a-f]*) return 1 ;; esac
    return 0
}

case $MODE in run|run-elevated|swap) ;; *) refuse "unknown mode" ;; esac
case $APP_PID in ''|*[!0-9]*) refuse "bad process id" ;; esac
case $VERSION in ''|*[!0-9.]*) refuse "bad version" ;; esac
is_absolute "$ROOT" || refuse "install root is not an absolute path"
is_absolute "$STAGING" || refuse "staging folder is not an absolute path"
is_absolute "$PLAN" || refuse "plan is not an absolute path"
is_absolute "$RESULT" || refuse "result is not an absolute path"
is_safe_relative "$LAUNCHER" || refuse "bad launcher path"
[ -d "$ROOT" ] || refuse "no install root"
[ -f "$ROOT/$LAUNCHER" ] || refuse "no launcher in the install root"
[ -d "$STAGING/files" ] || refuse "no staged files"
[ -f "$PLAN" ] || refuse "no plan"
case $PLAN in "$STAGING"/*) ;; *) refuse "the plan is not in the staging folder" ;; esac
if [ "$MODE" = run-elevated ]; then
    is_absolute "$ELEVATOR" || refuse "no elevation program"
    [ -x "$ELEVATOR" ] || refuse "the elevation program can't be run"
fi
if [ "$MODE" = swap ]; then
    case $OWNER_UID in -|*[!0-9]*) refuse "bad owner" ;; esac
    case $OWNER_GID in -|*[!0-9]*) refuse "bad owner group" ;; esac
fi

FILES="$STAGING/files"
BACKUP="$STAGING/backup"
WORK="$STAGING/apply"
STATE="$STAGING/apply.state"
# How long to wait for the app to close. The tests shorten it; it is only a number.
WAIT_SECONDS=${LUCENSE_UPDATE_WAIT_SECONDS:-60}
case $WAIT_SECONDS in ''|*[!0-9]*) WAIT_SECONDS=60 ;; esac

# The app watches this file: "ready" means the helper is running (with the
# rights it needs) and the app may exit; "cancelled"/"denied" mean it must not.
write_state() {
    printf '%s\n' "$1" > "$STATE.tmp" && mv -f -- "$STATE.tmp" "$STATE"
}

json_text() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | LC_ALL=C tr -d '\000-\037'
}

write_result() {
    mkdir -p -- "$(dirname -- "$RESULT")"
    if [ "$1" = true ]; then
        printf '{"ok":true,"version":"%s"}\n' "$VERSION" > "$RESULT.tmp"
    else
        printf '{"ok":false,"version":"%s","error":"%s"}\n' "$VERSION" "$(json_text "$2")" > "$RESULT.tmp"
    fi
    mv -f -- "$RESULT.tmp" "$RESULT"
}

result_ok() {
    [ -f "$RESULT" ] && grep -q '"ok":true' "$RESULT"
}

sha_of() {
    sha256sum < "$1" | cut -c1-64
}

# Inside the steps below: note what went wrong for the user and stop the step.
fail() {
    printf '%s\n' "$1" > "$WORK/error"
    say "$1"
    exit 1
}

wait_for_app() {
    waited=0
    while kill -0 "$APP_PID" 2>/dev/null; do
        if [ "$waited" -ge "$WAIT_SECONDS" ]; then return 1; fi
        sleep 1
        waited=$((waited + 1))
    done
    return 0
}

# Makes the folders above $1 that are missing, noting each, so a rollback can
# take away exactly the folders this made and no others.
make_parents() {
    parent=$(dirname -- "$1")
    missing=$parent
    while [ ! -d "$missing" ]; do
        printf '%s\n' "$missing" >> "$WORK/dirs.list"
        missing=$(dirname -- "$missing")
    done
    mkdir -p -- "$parent" || fail "Couldn't make the folder $parent."
}

# Every check that can be made before anything changes, then the backup, then
# the swap. Runs in a subshell with set -e: the first failure ends it.
swap_files() {
    : > "$WORK/backed-up.list"
    : > "$WORK/created.list"
    : > "$WORK/touched.list"
    : > "$WORK/dirs.list"
    while IFS="$TAB" read -r first second third; do
        [ -n "$first" ] || continue
        if [ "$first" = stale ] && [ -z "$third" ]; then
            is_safe_relative "$second" || fail "The plan names an unsafe path."
            # Only old jars beside the app's are ever removed.
            case $second in *.jar) ;; *) fail "The plan would remove something that isn't an old jar: $second" ;; esac
            target=$second
        else
            is_safe_relative "$first" || fail "The plan names an unsafe path."
            is_hex64 "$second" || fail "The plan has a bad checksum for $first."
            case $third in 0|1) ;; *) fail "The plan is damaged at $first." ;; esac
            # A plain staged file, not a link that could point anywhere.
            if [ ! -f "$FILES/$first" ] || [ -L "$FILES/$first" ]; then
                fail "The downloaded copy of $first is missing."
            fi
            target=$first
        fi
        dest="$ROOT/$target"
        if [ -e "$dest" ] || [ -L "$dest" ]; then
            # Only plain files are replaced: never a folder, never through a link.
            if [ ! -f "$dest" ] || [ -L "$dest" ]; then fail "$target in the install isn't a plain file."; fi
            mkdir -p -- "$(dirname -- "$BACKUP/$target")" || fail "Couldn't make the backup folder."
            cp -p -- "$dest" "$BACKUP/$target" || fail "Couldn't back up $target."
            printf '%s\n' "$target" >> "$WORK/backed-up.list"
        elif [ "$target" = "$first" ]; then
            printf '%s\n' "$target" >> "$WORK/created.list"
        fi
    done < "$PLAN"

    while IFS="$TAB" read -r first second third; do
        [ -n "$first" ] || continue
        if [ "$first" = stale ] && [ -z "$third" ]; then
            printf '%s\n' "$second" >> "$WORK/touched.list"
            rm -f -- "$ROOT/$second" || fail "Couldn't remove the old $second."
            continue
        fi
        dest="$ROOT/$first"
        make_parents "$dest"
        printf '%s\n' "$first" >> "$WORK/touched.list"
        # Written beside the old file and renamed over it: a file is always
        # either the whole old one or the whole new one.
        rm -f -- "$dest.lucense-new"
        cp -- "$FILES/$first" "$dest.lucense-new" || fail "Couldn't write $first."
        if [ "$third" = 1 ]; then mode=0755; else mode=0644; fi
        chmod "$mode" "$dest.lucense-new" || fail "Couldn't set the permissions of $first."
        mv -f -- "$dest.lucense-new" "$dest" || fail "Couldn't put $first in place."
    done < "$PLAN"
}

# Every installed file must hash to what the release says, and be executable
# where it should be; every old jar must be gone.
verify_files() {
    while IFS="$TAB" read -r first second third; do
        [ -n "$first" ] || continue
        if [ "$first" = stale ] && [ -z "$third" ]; then
            if [ -e "$ROOT/$second" ]; then fail "The old $second is still there."; fi
            continue
        fi
        dest="$ROOT/$first"
        [ -f "$dest" ] || fail "$first is missing after installing."
        [ "$(sha_of "$dest")" = "$second" ] || fail "$first doesn't match the release after installing."
        if [ "$third" = 1 ] && [ ! -x "$dest" ]; then fail "$first isn't executable after installing."; fi
    done < "$PLAN"
}

# Puts back every file the swap touched, takes away the ones it made, and
# checks the result byte for byte against the backup.
roll_back() {
    problems=0
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        rm -f -- "$ROOT/$path.lucense-new" || problems=1
        if grep -qxF -- "$path" "$WORK/backed-up.list"; then
            if cp -p -- "$BACKUP/$path" "$ROOT/$path.lucense-new" && mv -f -- "$ROOT/$path.lucense-new" "$ROOT/$path"; then
                :
            else
                problems=1
                say "couldn't put back $path"
            fi
        elif grep -qxF -- "$path" "$WORK/created.list"; then
            rm -f -- "$ROOT/$path" || problems=1
        fi
    done < "$WORK/touched.list"
    if [ -s "$WORK/dirs.list" ]; then
        # Deepest first; rmdir leaves alone any folder that isn't empty.
        sort -r -u "$WORK/dirs.list" | while IFS= read -r folder; do
            rmdir -- "$folder" 2>/dev/null || true
        done
    fi
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        if ! cmp -s -- "$BACKUP/$path" "$ROOT/$path"; then
            problems=1
            say "$path differs from its backup"
        fi
    done < "$WORK/backed-up.list"
    return "$problems"
}

# The whole update, with its outcome in result.json (see result_ok).
#
# Always called as a plain command and always returns 0: a shell ignores
# set -e inside anything run as a condition (if, ||, &&), even in a subshell
# that sets it again, so the steps that rely on it must never be reached
# from one.
apply_update() {
    if ! wait_for_app; then
        write_result false "Lucense didn't close within $WAIT_SECONDS seconds, so nothing was changed."
        return 0
    fi
    if [ -d "$BACKUP" ]; then mv -- "$BACKUP" "$BACKUP-$(date +%s)-$$"; fi
    rm -rf -- "$WORK"
    mkdir -p -- "$WORK" "$BACKUP"
    set +e
    (set -e; swap_files)
    status=$?
    if [ "$status" -eq 0 ]; then
        (set -e; verify_files)
        status=$?
    fi
    set -e
    if [ "$status" -eq 0 ]; then
        write_result true ""
        say "updated to $VERSION"
        return 0
    fi
    error="The update stopped part-way."
    if [ -s "$WORK/error" ]; then error=$(head -n 1 "$WORK/error"); fi
    set +e
    (roll_back)
    restored=$?
    set -e
    if [ "$restored" -ne 0 ]; then
        error="$error Putting the old files back failed too; please reinstall Lucense with the installer. The old files are in $BACKUP."
    fi
    write_result false "$error"
    say "rolled back: $error"
    return 0
}

# Started in a session of its own and with nothing attached, so it outlives
# this script. Its environment is this script's: the user's own.
start_app() {
    if command -v setsid >/dev/null 2>&1; then
        setsid "$ROOT/$LAUNCHER" </dev/null >/dev/null 2>&1 &
    else
        nohup "$ROOT/$LAUNCHER" </dev/null >/dev/null 2>&1 &
    fi
}

case $MODE in
    run)
        write_state ready
        # Lucense is started again however this ends, even if writing the
        # result itself failed: the user is never left without the app.
        trap start_app EXIT
        apply_update
        ;;
    run-elevated)
        # pkexec shows the system's password prompt and runs only the swap as
        # administrator; Lucense itself is started afterwards from here, as the
        # user, never as root.
        rm -f -- "$STATE"
        set +e
        "$ELEVATOR" /bin/sh "$0" swap "$APP_PID" "$ROOT" "$STAGING" "$VERSION" "$PLAN" "$RESULT" "$LAUNCHER" - "$(id -u)" "$(id -g)" </dev/null
        status=$?
        set -e
        if [ "$(cat "$STATE" 2>/dev/null || true)" != ready ]; then
            # The swap never started: the prompt was dismissed (126), the
            # rights were refused (127), or it stopped on its own checks. The
            # app is still running and says which.
            case $status in
                126) write_state cancelled ;;
                127) write_state denied ;;
                *) write_state failed ;;
            esac
            exit 0
        fi
        trap start_app EXIT
        if [ ! -f "$RESULT" ]; then
            write_result false "The update helper stopped unexpectedly (status $status)."
        fi
        ;;
    swap)
        write_state ready
        apply_update
        status=0
        result_ok || status=1
        # Made as root inside the user's own folders: handed back, so the app
        # can read the result and clear the staging folder later.
        chown -R -P "$OWNER_UID:$OWNER_GID" -- "$BACKUP" "$WORK" 2>/dev/null || true
        chown -h "$OWNER_UID:$OWNER_GID" -- "$RESULT" "$STATE" 2>/dev/null || true
        for old in "$BACKUP"-*; do
            if [ -d "$old" ]; then chown -R -P "$OWNER_UID:$OWNER_GID" -- "$old" 2>/dev/null || true; fi
        done
        exit "$status"
        ;;
esac
