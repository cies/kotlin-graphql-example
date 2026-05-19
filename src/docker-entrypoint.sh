#!/bin/sh
set -e
ROLE="${APP_ROLE:-web}"

# --- Auth env (Docker / Dokploy): fix common copy-paste mistakes before Node starts ---
# Next / Auth.js call `new URL(AUTH_URL)` with no try/catch; comma-separated values crash the app.
sanitize_comma_url_var() {
  var_name="$1"
  eval "current=\${$var_name-}"
  [ -z "$current" ] && return 0
  case "$current" in
    *,*)
      newval=$(printf '%s\n' "$current" | tr ',' '\n' | grep -oE 'https?://[^[:space:],]+' | head -1)
      if [ -n "$newval" ]; then
        export "$var_name=$newval"
      fi
      ;;
  esac
}

sanitize_comma_url_var AUTH_URL
sanitize_comma_url_var NEXTAUTH_URL
sanitize_comma_url_var NEXT_PUBLIC_APP_URL

# Strip Windows CR from env (some UIs paste CRLF into secrets).
if [ -n "${AUTH_SECRET:-}" ]; then
  AUTH_SECRET=$(printf '%s' "$AUTH_SECRET" | tr -d '\r')
  export AUTH_SECRET
fi
if [ -n "${NEXTAUTH_SECRET:-}" ]; then
  NEXTAUTH_SECRET=$(printf '%s' "$NEXTAUTH_SECRET" | tr -d '\r')
  export NEXTAUTH_SECRET
fi

if [ "$ROLE" = "workers" ]; then
  exec npx tsx scripts/workers.ts
fi

if [ -z "${AUTH_SECRET:-}" ] && [ -z "${NEXTAUTH_SECRET:-}" ]; then
  echo "crm: Missing AUTH_SECRET (or NEXTAUTH_SECRET). Add it to the app container environment in Dokploy / compose."
  exit 1
fi

npx prisma migrate deploy
exec npm run start
