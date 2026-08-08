# dcre-ptv

Payments Transaction Validator: DB-only DCRE stage (R-30) that validates every ENDO payment transaction against the account reference store and writes per-transaction verdicts to `validation_log` in `dcre_pay`.

The box caption on the payments sheet is **"Validates TxHeader & TxEntries"**.

## What it does

PTV is the second stage of the payments request DAG:

```
PAYMENTS  onhost-req-pay:  PRR -> PTV -> PAI -> { PRW -> Fintegrate request
                                               || PIR -> OnHost response }
```

AGT launches it as a short-lived Kubernetes Job per arrival (identifying job parameter `arrival.id`, a UUID). It has no file I/O of its own and transitions strictly via the database (R-30). The job runs four phases: a tier-1 header/count check (`FILE_FATAL` on mismatch), a set-based SQL duplicate scan, a partitioned per-transaction validation pass, and a verdict rollup that applies per-client acceptance mode (R-41). The rollup verdict is staged to an outcome seam file that AGT reads.

### Why this repo exists: the payments split

PTV was forked from [dcre-ctv](https://github.com/sean-huni/dcre-ctv) and then REDUCED, rather than CTV being renamed, so CTV keeps its history and its repo (the maf-to-mas precedent in this estate). Design: `design-register/docs/specs/2026-08-07-payments-family-build-design.md`, SCRUM-107.

Two things were removed, and they are the point of the fork:

- **The `flow` discriminator.** CTV branches on `dcre.flow-dc` because one service serves DC collections and ENDO payments out of one database. PTV writes `dcre_pay`, so the DATABASE is the discriminator and there is nothing left to branch on. `PayFlowOnlyTest` asserts this structurally, not by scanning for literals.
- **The mandate gate, and the `dcre_man` datasource with it.** The gate is DC-only (R-19/R-20), and SPEC-ENDO-COLLECTIONS-FLOW.md is explicit: *"No mandates, no mandate gate."* Keeping `MandatesDatasourceConfig` would open a cross-context connection to the mandates database on every payments arrival for a snapshot nothing reads, and would take payments down whenever mandates is unreachable. That coupling is exactly what the split removes, so `PtvApplication` imports three configs where `CtvApplication` imports four.

Also dropped in the reduction: `platform-copybook` (PTV never reads a file; CTV carries it only for the DC manifest-parity suite), and the hand-rolled `Dockerfile` (this repo builds its image with Paketo buildpacks, as PRR does).

## Architecture and principles

- SOLID, 3-tier: thin tasklet entry adapters (`HeaderCheckTasklet`, `DupScanTasklet`, `ValidationRangeTasklet`, `PtvTasklet`) each extract inputs and call one business-tier method; business logic lives in `service/` (`ValidationService`, `DupScanService`, `VerdictChain`); persistence happens only through `data/repo`.
- Layer-first packages: `batch/`, `config/`, `data/model/`, `data/repo/`, `service/` under `za.co.fnb.dcre.ptv`; `ValidationLogEntity` extends the platform `BaseEntity` (version/created_at/updated_at).
- 12FactorApp Alignment - https://12factor.net/ : config strictly from the environment with committed working dev defaults (a clean clone runs with NO `.env`), stateless one-shot process, the database and exchange directory as attached backing resources.
- Idempotent restart semantics: every verdict write is `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` (rerun no-op, R-05; dup-scan verdicts are never overwritten by a later per-tx verdict). A killed pod cannot strand the relaunch: an `@Order(-10)` ApplicationRunner runs `StaleExecutionSweeper.abandonStale(ds, "PTV_BATCH_", 60)` before the job starts (A-39a). CockroachDB serialization aborts (SQLSTATE 40001, surfacing as `TransientDataAccessException` at the chunk-commit boundary) are retried at step level by the shared platform-batch `CrdbRetryExceptionHandler("PTV")` (max 5 attempts, exponential backoff from 100 ms with jitter) on the two WRITING steps only: retry, never skip.

### Job structure

`ptvJob` (R-41) is a four-step flow; identifying JobParameter: `arrival.id` (UUID string).

1. `headerCheckStep` (tier 1, file-fatal): spine row count vs `tx_header.tx_count`; mismatch sets exit status `FILE_FATAL` + `fileFatalReason` in the execution context and ends the flow (no item verdicts). On success it captures the F51 as-of snapshot timestamp (`cluster_logical_timestamp()`) and the client token (`tx_header.initg_pty`) into the job execution context. ONE snapshot: CTV captures a second one against `dcre_man`, and PTV has no second store to pin.
2. `dupScanStep` (sequential, set-based): two SQL window-function inserts, in precedence order, duplicate e2e (first-wins, in-file scope R-25) then duplicate content-hash (`FAIL_DUPLICATE_TX`, R-41). Both `ON CONFLICT (arrival_id, sequence) DO NOTHING`, which encodes first-occurrence-wins and e2e-over-content precedence on a row that is both.
3. `validationStep` (partitioned): `SequenceRangePartitioner` splits `[1, txCount]` into contiguous ranges, grid size = `PartitionSizer.partitions(dcre.ptv.max-partitions)` (cgroup-aware CPU count), workers on a `VirtualThreadTaskExecutor`. Each worker runs `VerdictChain.classify` over its range against the shared as-of snapshot, skipping rows the dup scan already verdicted, and batch-writes verdicts (500 rows/statement, `DO NOTHING`, phase-1-dup-wins).
4. `rollupStep`: counts non-PASS verdicts and emits the business exit status by the client's acceptance mode (see Outcome seam).

### The verdict chain

`VerdictChain.classify(entry, accounts)` is the account tier and nothing else: account exists -> account active -> account cap (balance for balance-carrying products, else `max_credit_limit`). Duplicate rules are NOT in this chain; they run set-based in `dupScanStep` before it.

[SYNTHETIC-CONTRACT R-35] The pass-through semantics are CTV's ENDO-mode ones (A-20 draft), now unconditional: an unknown account and a known account with a NULL cap both **PASS**, because PAI creates absent accounts downstream (create-if-absent, R-11) and the cap check applies post-init. An EXISTING account that is inactive or over its cap still fails.

Reachable outcomes, and the full list of them:

| Outcome | When |
|---|---|
| `PASS` | admissible, including an unknown account and a NULL-cap account |
| `FAIL_ACCOUNT_NOT_ACTIVE` | the account exists and `process_status <> 'ACTIVE'` |
| `FAIL_EXCEEDS_RF_BALANCE` | balance-carrying product, amount over `balance` |
| `FAIL_EXCEEDS_CC_LIMIT` | limit product, amount over `max_credit_limit` |
| `FAIL_DUPLICATE_E2E` | in-file EndToEndId repeat (dup scan, R-25) |
| `FAIL_DUPLICATE_TX` | in-file content-hash clash (dup scan, R-41) |

`FAIL_ACCOUNT_NOT_FOUND` is the DC verdict for an unknown account and is **unreachable here**. So is every mandate-tier outcome (`FAIL_MANDATE_*`, `FAIL_CONTRACT_MISMATCH`, `FAIL_EXCEEDS_MANDATE_CAP`): there is no mandate tier.

R-38 exclusion visibility: one WARN per non-PASS verdict at decision time (dup scan and per-tx pass alike), shape `excluded stage=PTV arrival=<id> seq=<n> e2e=<e2e> reason=PTV_<OUTCOME>`; `validation_log` remains the durable record.

### Outcome seam

`afterJob` on COMPLETED only: writes `<exchange-root>/outcomes/<JOB_NAME>` (staged, atomic, via the platform `OutcomeFileWriter`). The four literals AGT can read:

- `BUSINESS_FILE_FATAL` (tier 1 count mismatch),
- `BUSINESS_FILE_REJECTED` (rollup: acceptance mode `ALL_OR_NOTHING` and any business FAIL -> whole file rejected, R-41),
- `BUSINESS_PARTIAL` (rollup: acceptance mode `PARTIAL` and any business FAIL -> PASS rows proceed, failing rows excluded),
- `BUSINESS_ACCEPTED` (clean file, either mode).

Per-client acceptance mode resolves from the header client token via `dcre.ptv.acceptance-mode` (default + per-client override map); binding is directly to the enum, so an unknown configured mode fails startup (fail closed). The rollup mirrors its decision into the job execution context (`seamVerdict`) because `afterJob` runs before the flow's terminal exit code is applied; the listener reads that, not `getExitStatus()`. Technical death writes nothing: the R-34 exit code (`ExitCodeMain`) and the K8s condition are the witnesses; AGT treats absence as never-success (R-33 arbiter clause). `JOB_NAME` comes from the env (falls back to `local-ptv-<executionId>`).

### Data

All in `dcre_pay`. No cross-database read of any kind.

Reads (grants-based, R-04/R-06): `tx_header` + `tx_entry` (PRR-owned spine; the dup scan reads `tx_entry.content_hash`, populated by PRR at ingest), `account` (PAI is the writer, R-11; bootstrapped `IF NOT EXISTS` by PAI's `000-bootstrap` changeset). `tx_entry.mandate_ref` exists on the shared physical layout and PRR persists it, but PTV does not map it: there is no gate to feed it to.

Writes: `validation_log` (PTV single writer, R-04; UNIQUE(arrival_id, sequence)), batched 500 rows/statement.

Liquibase: `db/changelog/db.changelog-master.xml`; per-service history tables `ptv_databasechangelog` / `ptv_databasechangeloglock` (same isolation idea as `PTV_BATCH_`). Two changesets, both `onFail="CONTINUE"` and never `MARK_RAN` (a MARK_RAN skip is recorded permanently, which has cost this project two defects, A-79 and A-81): 001 creates `validation_log` with its BaseEntity columns declared in the `createTable` and its unique constraint, 002 applies the `PTV_BATCH_` metadata DDL from a SQL file carrying `IF NOT EXISTS` on every `CREATE` plus `<validCheckSum>ANY</validCheckSum>`.

Spring Batch metadata lives under the `PTV_BATCH_` prefix (A-39b) with `initialize-schema: never` (Liquibase owns the DDL).

## Prerequisites

- Java 25 (`.sdkmanrc` pins `25-tem`)
- Docker (Testcontainers in tests, Paketo image build for the cluster)
- Platform libraries in Maven Local (no remote repository): run `./gradlew publishToMavenLocal` in each dependency repo, publish chain `dcre-platform-model` -> `dcre-platform-files` -> `dcre-platform-batch`; `dcre-platform-persistence` is standalone. Declared directly: `za.co.fnb.dcre:platform-persistence:0.1.0` (`BaseEntity`, `JdbcConfig`) and `za.co.fnb.dcre:platform-batch:0.1.0` (`ExitCodeMain`, `OutcomeFileWriter`, `StaleExecutionSweeper`, `PartitionSizer`, `CrdbRetryExceptionHandler`); `platform-model` (`CtvOutcome`, `ProductType`) and `platform-files` arrive transitively via `platform-batch`'s `api` chain.
- A reachable CockroachDB for a local run (the dcre-infra kind cluster with `scripts/crdb-forward.sh`, or any CRDB on `localhost:26257`)

## Quickstart

```bash
# platform libs published to Maven Local first (see Prerequisites)
./gradlew build          # compiles + full test suite (Docker required)
java -jar build/libs/ptv-2.0.jar 'arrival.id=<uuid>'
```

A clean clone runs with NO `.env`: committed defaults point at `localhost:26257/dcre_pay` and the dcre-infra exchange directory. Boot passes command-line args through as job parameters; the JVM exit code carries the Batch outcome (R-34).

## Configuration

Spring Boot 4.1.0, Java 25, `application.yml` only. Env overrides:

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_pay?sslmode=disable` | the payments CockroachDB |
| `DCRE_DB_USER` / `DCRE_DB_PASSWORD` | `root` / empty | DB credentials |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | outcome seam directory |
| `DCRE_PTV_MAX_PARTITIONS` | `5` | R-41 validation grid size cap (clamped to cgroup-aware CPU count) |
| `DCRE_PTV_ACCEPTANCE_MODE_DEFAULT` | `ALL_OR_NOTHING` | R-41 default acceptance mode |
| `DCRE_AGTOPS_DB_URL` / `_USER` / `_PASSWORD` | `…/agt_ops`, `root`, empty | heartbeat liveness stamp (M12) |
| `JOB_NAME` | `local-ptv-<executionId>` | outcome seam file name (set by AGT) |

There is deliberately **no** `DCRE_FLOW_DC`, no `DCRE_PTV_MANDATE_SOURCE` and no `DCRE_PTV_MANDATES_DB_*`. CTV needs all four; a payments stage that accepted them would be advertising behaviour it does not have.

Per-client acceptance overrides are yaml-only (no env var is wired for the map) and client tokens are UPPERCASE, so keys MUST be bracketed to survive relaxed binding: `dcre.ptv.acceptance-mode.clients.[FNBCC02]=PARTIAL`. `FNBCC02: PARTIAL` is a committed working default (SCRUM-42) so in-cluster partial-failure scenarios run without env passthrough. `DCRE_AMOUNT_SCALE` and `DCRE_V1_ENABLED` sit in the shared config block but are not consumed by PTV code.

## Testing

`./gradlew test` (Docker required; Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3`):

- `PayFlowOnlyTest`: the fork guard. No flow discriminator and no mandate coupling, asserted structurally (the `@Import` list, `TxEntryView`'s fields, `VerdictChain.classify`'s signature) as well as by literal scan, plus that the configured database is `dcre_pay`.
- `PtvVerdictSemanticsTest`: unknown-account and NULL-cap pass-through, inactive and over-cap still failing, and the exact R-38 WARN shape with a `stage=PTV` token.
- `DupScanServiceIT`: R-41 dup-scan precedence (content clash -> FAIL_DUPLICATE_TX, e2e clash -> FAIL_DUPLICATE_E2E, e2e wins on a row that is both).
- `PtvPartitionDeterminismIT`: same 10-row arrival under max-partitions 1 vs 5 yields identical (sequence, outcome) sets.
- `PtvSeamAndRollupIT`: the actual seam-file content for BUSINESS_FILE_REJECTED / BUSINESS_FILE_FATAL / BUSINESS_ACCEPTED, the self-describing local seam name, the zero-tx empty-partition path, and phase-1-dup-wins at the batch DAO.
- `AcceptanceModePropertiesTest`: `modeFor` resolution, fail-closed startup on an unknown mode, and the config-prefix red-proof (the committed map must BIND, not merely resolve to a plausible value).
- `PtvJobConfigRetryTest`: the shared CRDB retry handler is registered on the writing step, with the un-retried read-only step as the control.
- `CucumberSuiteTest`: account validation, job verdicts and per-client ALL_OR_NOTHING vs PARTIAL end to end (`src/test/resources/features/`). One suite and one glue package, where CTV needs two of each to keep its DC and ENDO contexts apart.

## Local cluster deployment

```bash
./gradlew bootBuildImage        # Paketo, BP_JVM_VERSION=25, produces dcre-ptv:2.0
kind load docker-image --name dcre-dev dcre-ptv:2.0
```

The fleet runs on the dcre-infra kind cluster (`scripts/kind-up.sh`); `scripts/switch-version.sh VERSION` points AGT at the tag. AGT then launches one Kubernetes Job per arrival with the `JOB_NAME` env and the identifying `arrival.id=<uuid>` program argument. Operational signals: the R-38 exclusion WARNs, the outcome seam file, and the R-34 exit code observed by AGT.

## Related repositories

- Orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt)
- Payments stages: [dcre-prr](https://github.com/sean-huni/dcre-prr) (upstream), [dcre-pai](https://github.com/sean-huni/dcre-pai) (downstream), [dcre-prw](https://github.com/sean-huni/dcre-prw), [dcre-pir](https://github.com/sean-huni/dcre-pir)
- Fork source: [dcre-ctv](https://github.com/sean-huni/dcre-ctv)
- Platform libraries: [dcre-platform-model](https://github.com/sean-huni/dcre-platform-model), [dcre-platform-files](https://github.com/sean-huni/dcre-platform-files), [dcre-platform-batch](https://github.com/sean-huni/dcre-platform-batch), [dcre-platform-persistence](https://github.com/sean-huni/dcre-platform-persistence)
- Environment and tooling: [dcre-infra](https://github.com/sean-huni/dcre-infra), [dcre-fixture-toolkit](https://github.com/sean-huni/dcre-fixture-toolkit), [dcre-design-register](https://github.com/sean-huni/dcre-design-register)
