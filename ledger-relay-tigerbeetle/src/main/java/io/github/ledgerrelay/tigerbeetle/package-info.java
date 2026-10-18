/**
 * TigerBeetle adapter. Implements the core outgoing port {@link io.github.ledgerrelay.core.spi.LedgerClient} with the
 * TB Java client. It only maps command kinds to TB flags and TB results to result codes; it makes no decisions.
 */
package io.github.ledgerrelay.tigerbeetle;
