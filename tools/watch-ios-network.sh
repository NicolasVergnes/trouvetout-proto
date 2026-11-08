#!/usr/bin/env bash
# N4, dynamic proof on the iOS simulator (lot 12, ADR-027): while `xcodebuild test` runs the app's tests — capture,
# search with the embedding model loaded (LiteRT-LM), export/import, purchases on the local StoreKit test session —
# every half second the app's processes (the app, which also hosts the unit tests, and its widget) are listed with
# `lsof -i`. Any internet socket, loopback included, is a violation. The simulator's apps are macOS processes, so
# the host sees their sockets; on a real iPhone this check stays manual (docs/07-recette.md).
#
#   tools/watch-ios-network.sh start <log-dir>   # in the background, before xcodebuild test
#   tools/watch-ios-network.sh stop <log-dir>    # after it: prints the findings, fails on a socket or no sighting
set -uo pipefail
cmd="${1:-}"
dir="${2:-build/ios-network}"
PATTERNS='iosApp[.]app/iosApp|CaptureWidget[.]appex/CaptureWidget'

case "$cmd" in
  start)
    mkdir -p "$dir"
    : > "$dir/sockets.log"
    : > "$dir/seen.log"
    rm -f "$dir/stop"
    (
      while [ ! -f "$dir/stop" ]; do
        for pid in $(pgrep -f -E "$PATTERNS" 2>/dev/null); do
          echo "$pid" >> "$dir/seen.log"
          lsof -nP -a -i -p "$pid" 2>/dev/null | tail -n +2 >> "$dir/sockets.log"
        done
        sleep 0.5
      done
    ) &
    echo $! > "$dir/watcher.pid"
    echo "watching the app's sockets (pid $(cat "$dir/watcher.pid"))"
    ;;
  stop)
    touch "$dir/stop"
    if [ -f "$dir/watcher.pid" ]; then
      wait "$(cat "$dir/watcher.pid")" 2>/dev/null || sleep 1
    fi
    seen=$(sort -u "$dir/seen.log" 2>/dev/null | wc -l | tr -d ' ')
    echo "== Dynamic network check, iOS simulator =="
    echo "app processes observed: $seen"
    if [ "$seen" -eq 0 ]; then
      echo "FAILURE: the app was never seen running; nothing was proven"
      exit 1
    fi
    if [ -s "$dir/sockets.log" ]; then
      echo "VIOLATION: internet sockets opened by the app:"
      sort -u "$dir/sockets.log"
      exit 1
    fi
    echo "OK: no internet socket during the whole test run"
    ;;
  *)
    echo "usage: $0 start|stop [log-dir]" >&2
    exit 2
    ;;
esac
