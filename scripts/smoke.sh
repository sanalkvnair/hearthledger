#!/bin/sh
# Basic end-to-end checks against a running stack: $1 = base URL.
set -eu
base="${1:-http://localhost:8081}"
fail() { echo "FAIL: $1"; exit 1; }

curl -fsS "$base/" | grep -qi "<div id=\"root\"" || fail "frontend not served"
code=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/me")
[ "$code" = "401" ] || fail "/api/v1/me expected 401, got $code"
curl -sI "$base/" | grep -qi '^x-content-type-options: nosniff' || fail "missing security headers"
loc=$(curl -s -o /dev/null -w '%{redirect_url}' "$base/oauth2/authorization/google")
echo "$loc" | grep -q "accounts.google.com" || fail "Google redirect missing ($loc)"
echo "smoke OK"
