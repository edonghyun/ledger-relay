# Benchmarks

What the relay sustains on one laptop, where it stops scaling, and why. Every run also checks that no money moved twice and no command was lost. A run that fails that check reports no numbers.

## What is measured

The library is assembled directly (core, jdbc, tigerbeetle), without Spring or HTTP. A "worker" is one app instance: its own thread, in-flight slots, TB client and connection pool (4). The workers share only PostgreSQL and TigerBeetle.

- **Drain**: accept N commands with the workers off, then start them and time until the last command finishes. This is the most the relay can finish per second.
- **Steady load**: accept at a fixed rate for 30 s with the workers on (the first 5 s are discarded), at 50% and 80% of the drain median for the same condition. This gives the time from acceptance to finish (p50/p99) and the acceptance time itself (p99).
- **Ordering keys**: `spread` (one command per key), `hundred` (100 keys), `one` (all commands on one key). Commands of one key are sent one at a time (G2, G4), so `one` shows the cost of ordering.
- Each command is a 100-unit transfer between two accounts opened for that run. Drain uses 20,000 commands (2,000 for `one`). Each condition runs 3 times; tables show the median with [min–max].

**Correctness check after every run**: no command left unfinished; reconciliation finds no command whose TB state differs from its recorded result; the credit account holds exactly as many transfers as there are applied commands (no duplicate, nothing extra).

## Environment

- MacBook (macOS 26.5, Apple silicon, 16 cores), Docker Desktop 28.5.1 with 16 CPUs and 7.65 GiB for the VM.
- JVM: OpenJDK 21.0.12, max heap 2 GiB. The workers run in this JVM, on the host.
- PostgreSQL 18.6 in a container with its default durability (`fsync` on), `shared_buffers=256MB`, `max_connections=300`.
- TigerBeetle 0.17.9, one replica, **production configuration** (no `--development`), `--cache-grid=512MiB`.
- Commit `9d49abe`. Other containers on the same VM used at most 15% of one CPU in total during the run (sampled every 30 s).

## Reproduce

```
./gradlew :ledger-relay-bench:run --args="matrix --out build/bench/03c"
./gradlew :ledger-relay-bench:run --args="single --measure drain --workers 4 --keys spread --batch 128 --commands 20000"
```

The matrix takes about 70 minutes. Output: `ledger-relay-bench/build/bench/<out>/results.csv` (one line per run) and `summary.md`. Stop other heavy containers first (see Limits).

Numbers below are copied from `summary.md`: rates in whole commands per second, latencies in whole milliseconds (TB requests in microseconds).

## Drain: commands finished per second (batch 128)

| workers | spread | hundred keys | one key |
|---|---|---|---|
| 1 | 407 [395–424] | 433 [276–448] | 216 [214–218] |
| 2 | 781 [721–834] | 821 [747–827] | 214 [211–214] |
| 4 | 1196 [1168–1372] | 1145 [1002–1187] | 211 [209–212] |
| 8 | 1193 [950–1576] | 1774 [1363–1780] | 203 [182–204] |

## Drain: batch size (4 workers, spread)

| batch | finished/s | TB request p50 / p99 |
|---|---|---|
| 32 | 1326 [1280–1385] | 768 / 5424 µs |
| 128 | 1196 [1168–1372] | 1646 / 374664 µs |
| 512 | 1347 [1321–1465] | 3724 / 13216 µs |

## Steady load

| workers | keys | rate (/s) | finish p50 | finish p99 | accept p99 |
|---|---|---|---|---|---|
| 1 | spread | 203 | 10 ms | 52 ms | 7 ms |
| 1 | spread | 325 | 15 ms | 48 ms | 4 ms |
| 2 | spread | 390 | 15 ms | 1180 ms | 11 ms |
| 2 | spread | 625 | 25 ms | 4109 ms | 40 ms |
| 4 | spread | 598 | 20 ms | 741 ms | 24 ms |
| 4 | spread | 957 | 664 ms | 2444 ms | 12 ms |
| 8 | spread | 597 | 18 ms | 877 ms | 8 ms |
| 8 | spread | 955 | 35 ms | 226 ms | 9 ms |
| 1 | one | 108 | 10 ms | 108 ms | 19 ms |
| 1 | one | 173 | 6841 ms | 8858 ms | 67 ms |
| 2 | one | 107 | 8 ms | 265 ms | 26 ms |
| 2 | one | 171 | 887 ms | 1470 ms | 8 ms |
| 4 | one | 106 | 8 ms | 252 ms | 10 ms |
| 4 | one | 169 | 1150 ms | 1825 ms | 64 ms |
| 8 | one | 102 | 6 ms | 18 ms | 5 ms |
| 8 | one | 162 | 12 ms | 257 ms | 12 ms |

The acceptance rate was held in every run.

## Correctness

90 runs in the matrix, 90 passed: no unfinished command, no reconciliation mismatch, no duplicate or extra transfer in TB. The other runs made for this document (the two discarded matrices and single runs, about 150) also passed, except two single runs stopped by hand while investigating F48, which never reached the check.

## Where it stops scaling

The verdicts below use one of four causes: TB request saturation, PostgreSQL lock or I/O wait, ordering key serialization, or a limit of the measuring tool. A verdict with only one piece of evidence is marked *estimate*.

**One worker is bound by PostgreSQL, not TB.** With one worker and spread keys, one TB request of 127 transfers takes 1.5 ms (p50), while the worker finishes 407 commands per second: about 2.5 ms per command, almost all of it in PostgreSQL. A worker writes each command in its own transaction twice (recording the submission, then the result), one after another on its thread. Writing each command separately keeps one failed write from losing the results the others received, and this is its price. Sampled PostgreSQL sessions spend 69% on CPU and 8% in `IO:WalSync`.

**Workers scale until the WAL flush is shared: PostgreSQL lock and I/O wait.** Spread throughput goes 407 → 781 (×1.9) → 1196 (×1.5) → 1193 (×1.0) from 1 to 8 workers. Over the same steps the share of sampled sessions waiting on `LWLock:WALWrite` goes from none in the top three to 46% at 8 workers. Commits from all workers queue for the same WAL flush, so more workers add waiting, not throughput.

**Batch size does not matter in this range.** At 4 workers, batches of 32, 128 and 512 give 1326, 1196 and 1347 with overlapping ranges. Cost is per command (the two commits), not per TB request, which fits the point above.

**One key is ordering key serialization, as designed.** 216, 214, 211, 203 per second for 1 to 8 workers. Only the head of a key is a claim candidate, so the next command waits for the previous one to finish: about 4.7 ms per command for claim, recording the submission, the TB request (0.46 ms p50) and the result. Extra workers only find nothing to claim (idle cycles 0% → 85%). The slight drop at 8 workers is idle workers' claim queries competing with the one working; *estimate*. An earlier run on a shared VM gave 78 per second at one worker (see Limits); run alternately with 8 workers on a quiet VM, both gave 167–216, so the flat line holds.

**Under steady load, acceptance and the workers share the same limits.** At 80% of the drain rate with 2 or 4 workers on spread keys, the finish p99 is 2–4 s, and at 4 workers the batch grows to 71: a queue builds. The drain rate was measured with no acceptance running; in steady load each acceptance is another commit on the same WAL (`LWLock:WALWrite` 43–52% in those runs), so the sustainable rate is lower than the drain rate. For one key, acceptance also takes the key's row lock, which the worker needs to pass the head on (`Lock:transactionid` 17–54%). At 1 worker and 173 per second the queue never drains during the run (finish p50 6.8 s). Plan steady load for well under the drain numbers; 50% stayed under 1.2 s p99 in every condition.

**Not resolved.** At 8 workers, `hundred` (1774) beat `spread` (1193), but the ranges overlap (1363–1780 and 950–1576), so these runs do not show a real difference. A TB request p99 of 375–396 ms appears in the 2-, 4- and 8-worker spread drains while the p50 stays at 1.5–3 ms; it does not move the medians and is probably TB's periodic background work; *estimate*.

**A note on the idle-cycle column.** It counts cycles, not time. An idle cycle is short (one claim query and a 5 ms rest), so with large batches the last seconds of a drain, when only a few workers still hold commands, produce hundreds of idle cycles. With batch 512 the column reads 86% for a run that was busy most of its time. Do not read it as wasted worker time.

## Limits

- One laptop, one Docker VM, PostgreSQL and TB in containers next to each other. Read the numbers as relative (more workers, other key spreads, batch sizes), not as what a server would do.
- Other containers on the same VM change the numbers a lot. A run of the same matrix while another project's image build ran on the VM gave, for example, 176 instead of 407 per second for one worker, and min–max ranges up to twice as wide. It is kept out of the tables above.
- TB must not run with `--development` for this: a development replica takes at most 253 transfers per request and answers at most 8 clients (F48, `docs/development.md`). The first matrix ran that way; its batch 512 and 8-worker numbers measured those limits, and it is discarded.
- Not measured here: the comparison with the earlier design (E8), and 10 million rows of finished history (only the backlog query is checked to read waiting rows only, `CommandStoreContract`).
