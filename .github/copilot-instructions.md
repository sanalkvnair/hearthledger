# HearthLedger – Copilot instructions

Self-hosted family finance app. Kotlin/Spring Boot 4 backend (`backend/`, JDK 25, Gradle, Spring Data JDBC, Flyway, PostgreSQL) + React/Vite/TypeScript/Mantine frontend (`frontend/`). REST API under `/api/v1`, JWT auth (Google OAuth login).

## Build, test, run

```sh
# Backend (from backend/) – tests use Testcontainers, Docker must be running
./gradlew test
./gradlew test --tests 'com.hearthledger.period.PeriodCalculatorTest'            # single class
./gradlew test --tests 'com.hearthledger.Phase5IntegrationTest.someTestName'     # single test
JWT_SECRET=$(openssl rand -hex 32) ./gradlew bootRun                              # needs local Postgres (see README)

# Frontend (from frontend/) – no test runner or linter; type-check + build is the CI check
npm ci
npm run dev        # :5173, proxies /api to :8080
npm run build      # tsc --noEmit && vite build

# Full stack + smoke test (what CI runs)
JWT_SECRET=$(openssl rand -hex 32) docker compose up -d --build --wait
sh scripts/smoke.sh http://localhost:8081
```

## Architecture

- **Backend is package-by-feature** under `com.hearthledger.*` (`txn`, `account`, `loan`, `period`, `importing`, `recurring`, `creditcard`, `savings`, `budget`, `buffer`, `dashboard`, …). Each feature typically has a `*Controller`, optional `*Service`, and entity/repository in one file. Controllers often use `JdbcClient` SQL directly for reporting queries.
- **Multi-tenancy by family**: every user belongs to one family. `family/CurrentUser` resolves the caller from the JWT; **every family-scoped query must filter by `currentUser.familyId()`**, and referenced ids (accounts, members, categories) must be validated as belonging to that family. Use `requireWriter()` (everyone except VIEWER) / `requireAdmin()` (OWNER, ADMIN) for authorization.
- **Errors**: throw `ApiException` (helpers `notFound`, `forbidden`, `badRequest` in `common/Errors.kt`); `ApiExceptionHandler` turns them into `ProblemDetail` with a `code` property, which the frontend `ApiError` reads. Validation failures use code `VALIDATION`.
- **Schema** is managed only by Flyway migrations in `backend/src/main/resources/db/migration` (`V<n>__name.sql`); add a new version, never edit applied ones.
- **Period logic** (`period/PeriodCalculator`, `PeriodService`): budget periods start on a family-configured day (clamped to month length, optionally moved back to the previous business day using `HolidayCalendars`); dashboards, budgets and balances are all computed per period, not per calendar month.
- **Money model** (see README "Concepts"): amounts stored in the transaction currency with an FX rate to the family base currency (`BigDecimal`, `.money()` = scale 4). Transfers into SAVINGS/FD/INVESTMENT accounts count as savings; credit-card purchases are expenses on purchase date and paying the bill is a bank→card transfer (never double-counted). Joint accounts appear in full per owner but once in family totals.
- **Recurring rules** auto-generate transactions idempotently; **loan payments** are booked automatically with interest/principal split (`loan/LoanService`, amortization covered by `AmortizationTest`).
- **Importing** (`importing/`): CSV wizard with saved mappings, `importHash` duplicate detection, keyword auto-categorisation; PDF import goes through an OpenAI-compatible LLM (opt-in per family, account numbers masked before sending).
- **Auth/security**: `auth/SecurityConfig`, JWT cookies with refresh (`/api/v1/auth/refresh`), per-IP `RateLimitFilter`, security headers; config via `AppProperties` / `application.yml` env vars (see `.env.example`).
- **Frontend**: pages in `src/pages`, shared `src/api.ts` (`api()` wrapper: `/api/v1` prefix, cookie auth, auto-refresh on 401, throws `ApiError`), `src/hooks.ts` (React Query hooks, `useApiMutation` which reports errors and invalidates given query keys, `canWrite`/`isAdmin` role helpers mirroring backend roles), DTO types in `src/types.ts`.

## Conventions

- **i18n**: all UI strings live in `frontend/src/i18n.ts` with `en` and `sv`; `sv` is typed `typeof en`, so every new key must be added to **both** languages (build fails otherwise).
- **Backend tests** are `@SpringBootTest` + MockMvc integration tests importing `TestcontainersConfiguration`, authenticating via `SecurityMockMvcRequestPostProcessors.jwt()` (test JWT secret in `src/test/resources/application.properties`). Existing tests are grouped by phase (`Phase2…Phase5IntegrationTest`); pure logic has plain unit tests (`PeriodCalculatorTest`, `AmortizationTest`, `CsvParserTest`).
- Request DTOs are Kotlin data classes next to their controller with Jakarta validation annotations on `@field:`; KDoc comments are used to explain non-obvious fields.
- The project is AGPL-3.0; Playwright/e2e tests are intentionally absent (needs a fake OAuth provider).
