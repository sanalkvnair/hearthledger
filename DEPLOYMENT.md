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

## Auto-deploy to a home server (Windows + Docker Desktop)

On every push to `main`, CI runs the tests and smoke test, publishes the images to GHCR (`ghcr.io/<owner>/<repo>/{backend,frontend}:<commit sha>`), and a self-hosted runner on the server runs `scripts/deploy.ps1`: it pulls the repo, takes a `pg_dump` backup (last 14 kept in `backups\`), pulls the new images, restarts the stack, checks it responds and rolls back to the previous tag if it does not (database migrations are not reverted; restore from the backup if needed).

One-time setup on the server:

1. Clone the repo into a permanent folder (e.g. `C:\hearthledger`), create `.env` there (see `.env.example`; add `COMPOSE_PROFILES=tunnel` to keep Cloudflare Tunnel running) and start the stack once with `docker compose up -d --build`.
2. Docker Desktop: *Settings → General → Start Docker Desktop when you sign in*; enable automatic sign-in or run the runner service as a user in the `docker-users` group, otherwise containers do not start after a reboot.
3. GitHub → repo *Settings → Actions → Runners → New self-hosted runner → Windows*. Configure it with label `hearthledger` and install it as a service (`.\svc.cmd install` / `.\svc.cmd start`, or answer yes to the service prompt) under that Docker user.
4. Image pulls: make the two packages public (GitHub → Packages → each package → *Package settings → Change visibility*), or run `docker login ghcr.io` once on the server with a PAT that has `read:packages`.
5. Repo *Settings → Secrets and variables → Actions → Variables*: `DEPLOY_DIR` = the folder from step 1 (e.g. `C:\hearthledger`) and `DEPLOY_ENABLED` = `true`.
6. Repo *Settings → Actions → General*: require approval for workflows from outside collaborators. The repository is public and the runner executes workflow code on your machine, so never allow fork pull requests to run on it (the deploy job only runs on pushes to `main`).

Roll back manually with `powershell -File scripts\deploy.ps1 -Dir C:\hearthledger -ImagePrefix ghcr.io/<owner>/<repo> -Tag <older sha>`. Restore a backup with `gunzip` (e.g. via Git Bash) piped into `docker compose exec -T db psql -U hearthledger hearthledger` on an empty database.

## Hardening already in place

- Per-IP rate limits (default 600 req/min general, 30 req/min for login/auth/import previews; `app.rate-limit.general|sensitive`).
- Security headers (nosniff, frame deny, referrer policy, CSP in nginx, HSTS when `COOKIE_SECURE=true`), httpOnly SameSite cookies, family-scoped queries.
- Audit log of all successful create/update/delete API calls (who, method, path, entity), visible to Owners/Admins under *Audit log*. It records the action, not before/after values.

## Backups

All state is in PostgreSQL. Take a daily dump and test restores:

```sh
docker compose exec -T db pg_dump -U hearthledger hearthledger | gzip > backup-$(date +%F).sql.gz
gunzip -c backup-YYYY-MM-DD.sql.gz | docker compose exec -T db psql -U hearthledger hearthledger
```

On managed Postgres, enable the provider's point-in-time recovery. Flyway migrations run automatically on startup; deploy a new backend version only after a fresh backup.

## Smoke test

`scripts/smoke.sh http://localhost:8081` checks the frontend, the 401 on unauthenticated API calls, security headers and the Google redirect (also run in CI).
