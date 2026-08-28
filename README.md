# Claims API — case study reference implementation

This is the sample code referenced in the case study submission: the
existing **Claims System UI & API**, enhanced with the orchestration logic
that connects it to the **Client Registry System**, **Policy Manager
System** and **Payment System** — none of which were previously wired up to
it or to each other.

It is deliberately **not** production-ready (per the brief). It exists to
demonstrate coding standards, project organisation, and how the design in
the four architecture diagrams (`claims-architecture.drawio`) translates
into real code.

## How this maps to the diagrams

| Diagram | What it shows | Where in the code |
|---|---|---|
| **1 — System Context** | Before/after: the five systems, wired via the Claims System API | This whole project *is* the "after" Claims System API box |
| **2 — Container Diagram** | ALB → Claims API (ECS Fargate) → RDS / external systems | `application-prod.yml` targets RDS; external calls via the `client` package |
| **3 — Component Diagram** | Controller, Orchestrator, adapter clients, repository | `controller`, `service`, `client`, `repository` packages, 1:1 |
| **4 — Claim Sequence** | Submit → validate → approve → pay → webhook | `ClaimOrchestratorService`, exercised end-to-end in `ClaimsWorkflowIntegrationTest` |

## Project structure

```
src/main/java/com/insurer/claims/
  entity/      Claim (rich entity, owns its own state transitions), ClaimStatus,
               ClaimPriority, ClaimType
  dto/         Request/response records for the HTTP API (Bean Validation annotated)
  client/      Adapter interfaces for the three external systems + their DTOs -
               verdict-returning (validateClient/validatePolicy), not data lookups
  client/impl/ Mock adapter implementations (stand in for Client Registry,
               Policy Manager, Payment System — see class-level Javadoc on each
               for the demo data conventions they use)
  repository/  Spring Data JPA repository (analyst queue query, idempotency-key
               lookup, possible-duplicate lookup)
  service/     ClaimOrchestratorService (the workflow itself), DuplicateClaimDetector
               (non-blocking duplicate-claim signal), and the payment webhook
               signature verifier
  controller/  ClaimsController (intake/queue/approve/reject) and
               PaymentWebhookController (the async payment callback) - each
               catches its own exceptions and builds the HTTP response
               directly, rather than delegating to a central handler
  exception/   Domain exceptions + a thin GlobalExceptionHandler (bean
               validation only - see its Javadoc for why that one case can't
               be a controller-local try/catch)
```

Adapters are interfaces on purpose: swapping a mock for a real HTTP client
against the actual Client Registry / Policy Manager / Payment System means
implementing one interface each — nothing else in the codebase changes.

## Running it

Requires Java 21 and Maven. No external services needed — with no profile
active, `spring.profiles.default` resolves to `local` (`application-local.yml`),
which runs against an in-memory H2 database.

```bash
mvn spring-boot:run
```

### Configuration profiles

Environment-specific settings are split out of the shared `application.yml`
into one file per profile:

| File | Profile | Purpose |
|---|---|---|
| `application.yml` | *(always loaded)* | Shared config (app name, actuator, logging) + `spring.profiles.default: local` |
| `application-local.yml` | `local` (default) | In-memory H2, H2 console enabled — zero external setup |
| `application-prod.yml` | `prod` | RDS PostgreSQL + payment webhook secret, sourced from env vars (ECS task config / Secrets Manager) |

Activate a non-default profile with `-Dspring.profiles.active=prod` (or the
`SPRING_PROFILES_ACTIVE` env var). Add further environments — e.g. a QA
profile — the same way: create `application-qa.yml` with just what differs
from the shared file, no other changes needed.

Run the tests:

```bash
mvn test
```

All 22 tests pass: 15 unit tests on the orchestrator's decision logic
(`ClaimOrchestratorServiceTest`), 6 integration tests exercising the full
HTTP lifecycle against an in-memory H2 database
(`ClaimsWorkflowIntegrationTest`), and 1 test proving the optimistic-locking
fix under concurrent approval (`ClaimOptimisticLockingTest`). The API has
also been exercised live via curl and via the Postman collection below
(26 requests, 35 assertions, run through Newman with 0 failures).

## Trying the endpoints

A ready-to-import Postman collection + local environment covering the full
lifecycle (happy path, all four rejection reasons, analyst reject, duplicate
handling, and the error cases — 400/401/404/409) lives in `postman/`. Import
both files, select the "claims-api (local)" environment, and run folder
**1. Happy path** top-to-bottom — it chains the claim id and computes the
webhook HMAC signature for you via a pre-request script. The curl walkthrough
below covers the same ground manually.

Submit a claim (the mock adapters recognise `clientId`/`policyNumber`
conventions documented on `MockClientRegistryClient` and
`MockPolicyManagerClient` — this pair is a matching, active client/policy):

```bash
curl -s -X POST http://localhost:8080/claims/submit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: optional-channel-generated-key" \
  -d '{
    "clientId": "CL-1001",
    "claimantFullName": "Registered Client CL-1001",
    "claimantIdNumber": "ID-CL-1001",
    "policyNumber": "POL-CL-1001",
    "claimType": "DEATH",
    "incidentDate": "2026-08-01",
    "claimedAmount": 100000.00
  }'
```

A `DEATH` claim comes back `"priority":"HIGH"` and, on passing both
validations, `"status":"PENDING_ANALYST_APPROVAL"`. The response also
carries a `claimReference` (e.g. `"CLM-000123"`) alongside the UUID `id` -
a short, sequential, human-readable number an analyst can actually write
down or read out over the phone. It's generated by `ClaimReferenceGenerator`
from a dedicated `claim_sequence` table (plain JPA `IDENTITY` generation -
portable across H2 and Postgres without relying on either's native sequence
syntax) and is display-only: the UUID stays the real identifier for every
URL, foreign key and lookup in the system.

Check the analyst queue (HIGH priority first):

```bash
curl -s http://localhost:8080/claims/queue
```

The queue only ever shows claims still awaiting a decision
(`PENDING_ANALYST_APPROVAL`) - once you approve/reject one it drops out of
it, by design (that's what a triage queue is for). To browse every claim
regardless of status - handy while testing, so an approved/rejected/paid
claim doesn't just disappear on you - use `GET /claims` instead:

```bash
curl -s http://localhost:8080/claims
```

Approve it (replace `<id>` with the id from the submit response):

```bash
curl -s -X POST http://localhost:8080/claims/<id>/approve \
  -H "Content-Type: application/json" \
  -d '{"analystId": "analyst-1"}'
```

Simulate the Payment System's webhook. The signature is
`HMAC-SHA256(webhook-secret, eventId:claimId:outcome)`, hex-encoded — the
default secret is `dummy-shared-secret-change-me` (see `application-local.yml`):

```bash
CLAIM_ID=<id>
EVENT_ID=evt-001
PAYLOAD="${EVENT_ID}:${CLAIM_ID}:SUCCESSFUL"
SIG=$(printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "dummy-shared-secret-change-me" | awk '{print $NF}')

curl -s -X POST http://localhost:8080/claims/$CLAIM_ID/payment-callback \
  -H "Content-Type: application/json" \
  -H "X-Payment-Signature: $SIG" \
  -d "{\"eventId\":\"$EVENT_ID\",\"claimId\":\"$CLAIM_ID\",\"outcome\":\"SUCCESSFUL\",\"providerPaymentReference\":\"PROV-REF-1\"}"
```

Re-sending the same request is safe — `eventId` makes it idempotent.

Negative paths worth trying: a `clientId` prefixed `INACTIVE-`, a
`policyNumber` prefixed `EXPIRED-`, or a `claimantFullName` that doesn't
match the mock's convention — each rejects the claim with a specific
reason, without ever reaching the Payment System. A `clientId` prefixed
`DECLINE-` passes validation and reaches the Payment System, which then
refuses the request — the claim ends up `REJECTED` with reason "Payment
System declined the request: Insufficient provider balance", exercising the
one rejection path that isn't a validation failure.

### Duplicate claims

Two intentionally separate mechanisms guard against duplicate claims — see
Postman folder **4. Idempotency & duplicate detection**:

- **`Idempotency-Key`** (optional request header on `POST /claims/submit`,
  not a body field - it's transport plumbing, not claim data) resolves an
  accidental resend of the *exact same* submission — a network retry or a
  double-click on the controller form's submit button — to the *same*
  claim. Submit the same key twice and the second call returns the
  existing claim instead of creating a new one; nothing is validated twice.
- **`possibleDuplicateOfClaimId`** (on `ClaimResponse`) is a non-blocking
  signal from `DuplicateClaimDetector`, checked after a claim passes
  validation: if another non-rejected claim exists for the same policy +
  claim type + incident date, the new claim is flagged for the analyst to
  review — it still proceeds to `PENDING_ANALYST_APPROVAL` and is never
  auto-rejected, because two genuinely separate claims can share all three
  attributes (e.g. two unrelated medical claims on the same policy).

These are not redundant with each other, even though both are about
"duplicates" - they solve different problems and neither can substitute for
the other:

- `Idempotency-Key` is a **technical guarantee** for the *exact same request*
  arriving twice (a retry, a double-click). It short-circuits before any
  work happens: no second claim is created, nothing is re-validated, no
  analyst is involved. Same key in, same claim back out - zero judgment
  required.
- `possibleDuplicateOfClaimId` is a **business-judgment signal** for two
  requests that merely look similar but were never declared as the same
  request. It still creates the second claim - it only flags it. Without
  the idempotency key, every accidental retry would create a real second
  claim, re-run validation against the Client Registry and Policy Manager,
  land in the analyst's queue a second time, and rely on a human noticing
  the flag and rejecting the right one - turning a network hiccup into
  manual work and a live risk of double-approval if they miss it.

There is **no database uniqueness constraint on policy/client** — a policy
can have multiple legitimate claims, so that call is deliberately left to
the analyst rather than enforced by the schema.

## Assumptions

- **Analyst identity and authentication are managed by the existing Claims
  System and are out of scope for this enhancement.** The brief describes
  the Claims System (UI + API) as an existing system being enhanced, not
  built from scratch — it already has its own analyst accounts, login and
  roles; that isn't shown in the brief's diagrams only because it isn't
  new. `analystId` is treated as an opaque identifier supplied by the
  existing UI/session and is recorded purely for audit purposes
  (`Claim.approvedByAnalystId`) — there is no `Analyst` table, and building
  one would mean inventing an identity/auth model for a system that
  already has one.

## Known simplifications (and why)

- **Payment webhook idempotency is DB-backed, not an in-memory set** -
  `ProcessedPaymentEvent`'s unique `eventId` column, checked/recorded via
  `ProcessedPaymentEventRecorder`. This survives a restart and works across
  every ECS Fargate replica behind the ALB, unlike a plain in-memory `Set`.
  It correctly no-ops a plain sequential redelivery (the realistic case a
  webhook actually needs to handle - the provider retries because it didn't
  get a 200 in time). The check-then-insert isn't atomic, though: two
  deliveries of the same event arriving *genuinely concurrently* could both
  pass the existence check before either commits, and the loser fails on
  the unique constraint rather than being handed a clean no-op - see the
  class's Javadoc for why (a Spring transaction is marked rollback-only the
  instant an exception is thrown inside it, even if the code goes on to
  catch that exception, so isolating the write into its own `REQUIRES_NEW`
  transaction is the actual fix, deliberately not built here to keep this
  class simple).
- **Webhook signature is computed over a canonical string**
  (`eventId:claimId:outcome`), not the raw request body bytes. Real
  providers sign the exact bytes they sent; this sample signs a
  reconstructed string to avoid a raw-body-capturing filter, purely to keep
  the demo self-contained. See the Javadoc on `PaymentWebhookSignatureVerifier`.
- **Validation failures reject the claim outright**, rather than routing to
  a separate "needs review" state for a human to adjudicate edge cases
  (e.g. a near-miss name mismatch). That distinction came up while
  designing the workflow and is a genuine improvement worth making in a
  real system; it's called out as a recommendation rather than built here,
  to keep the state machine — `ClaimStatus` — demonstrable within scope.
- **Mock adapters use deterministic, documented conventions** (prefixes
  like `INACTIVE-`/`EXPIRED-`/`DECLINE-`, and `policyNumber = "POL-" + clientId`)
  instead of a real datastore, so the whole workflow — happy path and
  rejection paths alike — is exercisable with zero external setup. See the
  Javadoc on each `Mock*Client`.
- **The `Idempotency-Key` header check is read-then-write, not atomic** -
  unlike the payment webhook's idempotency check above, this one wasn't
  given the same `REQUIRES_NEW`-backed treatment. Two submissions with the
  same key arriving genuinely concurrently could both pass the
  `findByIdempotencyKey` check before either commits, and the loser would
  then fail outright on the DB's unique constraint (a 500) rather than
  being handed the winner's claim. A production implementation should
  apply the same pattern used for the payment webhook: attempt the insert
  in its own transaction and treat a constraint violation as "someone else
  already recorded it," not an error.
