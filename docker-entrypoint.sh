#!/bin/sh
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

for value in "$PUID" "$PGID"; do
  case "$value" in
    ''|*[!0-9]*)
      echo "PUID and PGID must be numbers, got PUID='$PUID' PGID='$PGID'" >&2
      exit 1
      ;;
  esac
  if [ "${#value}" -gt 10 ] || [ "$value" -gt 4294967294 ]; then
    echo "PUID and PGID must be at most 4294967294, got PUID='$PUID' PGID='$PGID'" >&2
    exit 1
  fi
done

export HOME=/tmp

warn() {
  echo "Warning: $*" >&2
}

prepare() {
  state_file="${CLIENT_STATE_FILE:-/var/lib/mediagarrd/client/state.json}"
  state_dir="$(dirname "$state_file")"
  state_base="$(basename "$state_file")"
  download_dir="${CLIENT_DOWNLOAD_DIRECTORY:-/var/lib/mediagarrd/downloads}"

  mkdir -p "$state_dir" "$download_dir" || warn "could not create $state_dir or $download_dir"

  for dir in "$state_dir" "$download_dir"; do
    if [ "$dir" = "/" ]; then
      warn "refusing to change the owner of /"
    else
      echo "Giving $PUID:$PGID ownership of $dir and the client's own files in it" >&2
      chown -h "$PUID:$PGID" "$dir" || warn "could not change the owner of $dir"
    fi
  done

  if [ -d "$state_dir" ] && [ "$state_dir" != "/" ]; then
    find "$state_dir" -maxdepth 1 -type f -links 1 \
      \( -name "$state_base" -o -name "$state_base.tmp" -o -name "$state_base.corrupt-*" \
         -o -name password.json -o -name password.json.tmp \) \
      -exec chown -h "$PUID:$PGID" {} + || warn "could not change the owner of the client's files in $state_dir"
  fi

  if [ -d "$download_dir" ] && [ "$download_dir" != "/" ]; then
    find "$download_dir" -maxdepth 1 -type f -links 1 \
      \( -name '*-media-backup.zip' -o -name '*-media-backup-AD.zip' -o -name '*-media-backup*.zip.part' \) \
      -exec chown -h "$PUID:$PGID" {} + || warn "could not change the owner of the client's archives in $download_dir"
  fi
}

uid="$(id -u)"
if [ "$uid" -eq 0 ] && [ "$PUID" -ne 0 ]; then
  prepare
  if ! command -v setpriv >/dev/null 2>&1; then
    echo "setpriv is not available in this image, cannot drop privileges" >&2
    exit 1
  fi
  exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups --no-new-privs -- "$@"
fi

exec "$@"
