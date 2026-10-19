package io.github.ledgerrelay.bench;

import com.tigerbeetle.Client;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.core.application.Backoff;
import io.github.ledgerrelay.core.application.CommandLifecycle;
import io.github.ledgerrelay.core.application.RelayOperator;
import io.github.ledgerrelay.jdbc.JdbcCommandStore;
import io.github.ledgerrelay.jdbc.JdbcOrderingKeys;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleLedgerClient;
import io.github.ledgerrelay.tigerbeetle.TigerBeetleTransferIds;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks what a run left behind, before any of its numbers count.
 *
 * <ul>
 *   <li>Nothing is missing: every accepted command reached a final state.</li>
 *   <li>PG and TB agree: the public reconciliation over every finished command finds no mismatch.</li>
 *   <li>Nothing is doubled: TB holds exactly as many transfers on the run's account as PG has applied commands.</li>
 * </ul>
 */
final class Correctness {

    private Correctness() {
    }

    record Verdict(long accepted, long unfinished, long mismatches, long applied, long transfersInLedger) {

        boolean passed() {
            return unfinished == 0 && mismatches == 0 && transfersInLedger == applied;
        }
    }

    static Verdict check(BenchEnvironment.Run run) {
        JdbcClient sql = JdbcClient.create(run.environment().admin());
        long accepted = sql.sql("SELECT count(*) FROM ledger_command").query(Long.class).single();
        long applied = sql.sql("SELECT count(*) FROM ledger_command WHERE state = 'APPLIED'").query(Long.class).single();
        return new Verdict(accepted, run.unfinished(), mismatches(run), applied, run.transfersInLedger());
    }

    private static long mismatches(BenchEnvironment.Run run) {
        try (Client client = run.environment().newClient()) {
            CommandLifecycle lifecycle = new CommandLifecycle(new JdbcOrderingKeys(run.environment().admin()),
                    new JdbcCommandStore(run.environment().admin()), run.transactions, new TigerBeetleTransferIds(),
                    BenchEnvironment.NO_OP, new Backoff(Duration.ofMillis(200), Duration.ofSeconds(2)));
            RelayOperator operator = new RelayOperator(new JdbcCommandStore(run.environment().admin()),
                    run.transactions, lifecycle, new TigerBeetleLedgerClient(client), Duration.ofSeconds(10));
            long mismatches = 0;
            long position = 0;
            while (true) {
                ReconciliationReport page = operator.reconcile(position, 1000);
                mismatches += page.mismatches().size();
                if (page.lastPosition() == position) {
                    return mismatches;
                }
                position = page.lastPosition();
            }
        }
    }
}
