#!/usr/bin/env bash
# N4, dynamic check on the iOS simulator (lot 12, ADR-027): while `xcodebuild test` runs the app's tests — capture,
# search with the embedding model loaded (LiteRT-LM), purchases on the local StoreKit test session — every half second
# the app's processes installed on the simulator (the app, which also hosts the unit tests, and its widget) are listed
# with `lsof -i`. Any internet socket, loopback included, is a violation. The simulator's apps are macOS processes, so
# the host sees their sockets. This is sampling: a connection opened and closed within half a second can slip through,
# and name lookups go through the host's mDNSResponder. It shows that no socket was observed, not that none can ever
# be opened; on a real iPhone the check stays manual (docs/07-recette.md).
#
#   tools/watch-ios-network.sh start <log-dir>   # in the background, before xcodebuild test
#   tools/watch-ios-network.sh stop <log-dir>    # after it: prints the findings, fails on a socket or no sighting
set -uo pipefail
cmd="${1:-}"
dir="${2:-build/ios-network}"
# Installed on a simulator device only: the build tools that write iosApp.app/iosApp (linker, codesign) do not match.
PATTERN='CoreSimulator/Devices/.*/(iosApp[.]app/iosApp|CaptureWidget[.]appex/CaptureWidget)( |$)'

case "$cmd" in
  start)
    mkdir -p "$dir"
    : > "$dir/sockets.log"
    : > "$dir/seen.log"
    (
      while true; do
        # BSD pgrep: patterns are extended regular expressions already (no -E).
        for pid in $(pgrep -f "$PATTERN"); do
          ps -o command= -p "$pid" >> "$dir/seen.log"
          lsof -nP -a -i -p "$pid" | tail -n +2 >> "$dir/sockets.log"
        done
        sleep 0.5
      done
    ) &
    echo $! > "$dir/watcher.pid"
    echo "watching the app's sockets (pid $(cat "$dir/watcher.pid"))"
    ;;
  stop)
    if [ -f "$dir/watcher.pid" ]; then
      watcher=$(cat "$dir/watcher.pid")
      kill "$watcher" 2>/dev/null
      while kill -0 "$watcher" 2>/dev/null; do sleep 0.2; done
    fi
    echo "== Dynamic network check, iOS simulator =="
    seen=$(sort -u "$dir/seen.log" 2>/dev/null | wc -l | tr -d ' ')
    echo "app processes observed: $seen"
    sort -u "$dir/seen.log" 2>/dev/null | sed 's/^/  /'
    if [ "$seen" -eq 0 ]; then
      echo "FAILURE: the app was never seen running on the simulator; nothing was observed"
      exit 1
    fi
    if [ -s "$dir/sockets.log" ]; then
      echo "VIOLATION: internet sockets opened by the app:"
      sort -u "$dir/sockets.log"
      exit 1
    fi
    echo "OK: no internet socket observed during the whole test run"
    ;;
  *)
    echo "usage: $0 start|stop [log-dir]" >&2
    exit 2
    ;;
esac
