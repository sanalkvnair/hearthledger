# HearthLedger

Self-hosted family finance app: income, expenses, savings and budgeting for a whole household.

- **Backend** – Spring Boot 4, Kotlin, Gradle, Java 25, Spring Data JDBC, Flyway, PostgreSQL (`/backend`)
- **Frontend** – React + Vite + TypeScript + Mantine (`/frontend`)
- **API** – REST under `/api/v1` (OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`), JWT auth so a mobile app can reuse it.

## Run with Docker

```sh
cp .env.example .env   # add Google OAuth credentials + a long random JWT_SECRET
docker compose up --build
```

If you get `unknown flag: --build`, your Docker has no Compose v2 plugin. Use the standalone
binary instead (`docker-compose up --build`) or install the plugin (`brew install docker-compose`
and follow its note about `cliPluginsExtraDirs`, or install Docker Desktop).

Open http://localhost:8081. In Google Cloud Console create an OAuth client with redirect URI
`http://localhost:8081/login/oauth2/code/google`. Set `COOKIE_SECURE=true` behind HTTPS.

## Develop

```sh
# database
docker run -d -p 5432:5432 -e POSTGRES_DB=familybudget -e POSTGRES_USER=familybudget -e POSTGRES_PASSWORD=familybudget postgres:17
cd backend && JWT_SECRET=$(openssl rand -hex 32) ./gradlew bootRun       # needs JDK 25 (or use the Docker build)
cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api to :8080
```

Backend tests use Testcontainers (Docker required): `./gradlew test`.

## PDF import (optional)

PDF statements are read by any OpenAI-compatible chat API. Set `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`
(e.g. `LLM_BASE_URL=http://ollama:11434/v1` keeps data local) and enable it per family under Family settings.
Account/IBAN-like numbers are masked before the text is sent. Scanned (image-only) PDFs are not supported.

## Concepts

- **Family & roles** – one family per user; Owner / Admin / Member / Viewer; invite via link.
- **Budget period** – family-configurable start day (e.g. 25 → 24th); a missing day uses the month's last day.
  Optionally the start moves back to the previous business day (weekends + public holidays of a chosen country), so a
  25 Oct 2026 start (Sunday) becomes 23 Oct; each period ends the day before the next one starts.
- **Balances** – shown with and without the previous balance. Previous balance is calculated
  (income − expenses − savings of earlier periods) and can be overridden with the actual amount.
- **Savings** – entries recorded as transfers into a savings / FD / investment account count as savings.
- **Credit cards** – card accounts have a statement day and a due day (never shifted for weekends). Purchases are expenses on the
  purchase date; paying the bill is a bank → card *transfer*, so nothing is counted twice. The dashboard lists upcoming bills
  and unpaid bills reduce the liquid savings used for the buffer. The expense chart can be filtered by payment method.
- **Account ownership** – Private (one owner), Joint (selected members) or Whole family. A joint account shows in full under each owner
  and once in family totals; the Savings page toggles All / Shared / Individual.
- **Investments** – record valuations as market value or profit/loss; the page charts invested amount against value over time.
- **Loans** – annuity, fixed amortization (% of the original principal per year + interest), interest-only, and annuity with balloon
  (residual value, down payment, fees, effective rate via IRR). Existing loans can start from an outstanding balance, and rate changes
  are effective-dated. Payments are booked automatically with their interest/principal split.
- **Faster entry** – default accounts per user, "Save & add another", and a bulk entry grid (all rows saved or none).
- **Currencies** – base currency per family; manual exchange rates (or per-entry rate).

## Status

- **Phase 1** – auth, family, categories, accounts, transactions, periods, dashboard, FX, i18n (en/sv), dark mode, Docker, CI.
- **Phase 2** – recurring rules (auto-generated, idempotent), loans/EMI with amortization schedules, savings screen (family + per member, FD maturity, manual valuation), category budgets with alerts, safety buffer (3/6 months, family or member) on the dashboard.
- **Phase 3** – CSV import wizard with saved mappings, duplicate detection, keyword auto-categorisation rules, PDF statement import via an LLM (opt-in), CSV/Excel export (Import / Export page).
- **Phase 4** – audit log (admins), per-IP rate limiting, security headers, smoke test in CI, [deployment & backup guide](DEPLOYMENT.md).
- **Phase 5** – business-day periods, bulk entry and default accounts, credit-card statements, joint/private accounts, investment valuations, extended loan types.

Browser-driven end-to-end tests (Playwright) are not included: they need a fake OAuth provider. Backend integration tests and `scripts/smoke.sh` cover the main paths.
