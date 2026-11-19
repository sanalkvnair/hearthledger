# Deployment

Two containers plus PostgreSQL: `frontend` (nginx serving the SPA and reverse-proxying `/api`, `/oauth2`, `/login/oauth2`) and `backend` (Spring Boot). Images are published to GHCR by CI (`ghcr.io/<owner>/<repo>/{backend,frontend}`).

## Configuration (environment variables)

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | PostgreSQL connection |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | OAuth client; redirect URI `https://<host>/login/oauth2/code/google` |
| `JWT_SECRET` | Required (no default; startup fails otherwise). ≥ 32 random bytes (`openssl rand -hex 32`); changing it logs everyone out |
| `FRONTEND_URL` | Public origin, e.g. `https://budget.example.com` |
| `COOKIE_SECURE` | `true` behind HTTPS (also enables HSTS) |
| `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` | Optional PDF import |

## PaaS (Render, Fly.io, Railway, Azure Container Apps, ...)

1. Create a managed PostgreSQL and pass its URL to the backend.
2. Deploy the backend image (port 8080, health check `/actuator/health`) on a private/internal address.
3. Deploy the frontend image publicly; set the nginx upstream `backend` host to the backend's internal name (edit `frontend/nginx.conf` or use a network alias called `backend`).
4. Terminate TLS at the platform and set `COOKIE_SECURE=true`.

Do not expose the backend directly to the internet: the rate limiter trusts the `X-Forwarded-For` header set by nginx. If another proxy sits in front of nginx, make sure it sets `X-Forwarded-For` and adjust nginx to use `$http_x_forwarded_for` (or the `realip` module).

## Hardening already in place

- Per-IP rate limits (default 600 req/min general, 30 req/min for login/auth/import previews; `app.rate-limit.general|sensitive`).
- Security headers (nosniff, frame deny, referrer policy, CSP in nginx, HSTS when `COOKIE_SECURE=true`), httpOnly SameSite cookies, family-scoped queries.
- Audit log of all successful create/update/delete API calls (who, method, path, entity), visible to Owners/Admins under *Audit log*. It records the action, not before/after values.

## Backups

All state is in PostgreSQL. Take a daily dump and test restores:

```sh
docker compose exec -T db pg_dump -U familybudget familybudget | gzip > backup-$(date +%F).sql.gz
gunzip -c backup-YYYY-MM-DD.sql.gz | docker compose exec -T db psql -U familybudget familybudget
```

On managed Postgres, enable the provider's point-in-time recovery. Flyway migrations run automatically on startup; deploy a new backend version only after a fresh backup.

## Smoke test

`scripts/smoke.sh http://localhost:8081` checks the frontend, the 401 on unauthenticated API calls, security headers and the Google redirect (also run in CI).
