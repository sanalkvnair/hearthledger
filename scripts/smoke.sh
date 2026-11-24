#!/bin/sh
# Basic end-to-end checks against a running stack: $1 = base URL.
set -eu
base="${1:-http://localhost:8081}"
fail() { echo "FAIL: $1"; exit 1; }

# the backend has no healthcheck, so wait (up to ~3 min) until nginx can reach it
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/me")" = "401" ]; do
  i=$((i + 1)); [ "$i" -lt 60 ] || break
  sleep 3
done

curl -fsS "$base/" | grep -qi "<div id=\"root\"" || fail "frontend not served"
code=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/me")
[ "$code" = "401" ] || fail "/api/v1/me expected 401, got $code"
curl -sI "$base/" | grep -qi '^x-content-type-options: nosniff' || fail "missing security headers"
loc=$(curl -s -o /dev/null -w '%{redirect_url}' "$base/oauth2/authorization/google")
echo "$loc" | grep -q "accounts.google.com" || fail "Google redirect missing ($loc)"
echo "smoke OK"
