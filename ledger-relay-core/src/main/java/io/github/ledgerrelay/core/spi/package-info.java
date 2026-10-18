/**
 * The outgoing ports of ledger-relay. They state, in domain values, what core needs from the outside.
 * jdbc (store, transactions), tigerbeetle (TB client) and the core testFixtures (in-memory store, fake TB) implement
 * them. The production transfer id generator ({@link TransferIdGenerator}) is implemented by
 * {@code TigerBeetleTransferIds} in ledger-relay-tigerbeetle, and the worker events ({@link WorkerEvents}) by
 * {@code LoggingWorkerEvents} in ledger-relay-spring-boot.
 * App code does not use this package. Ports make no decisions; core.application does.
 */
package io.github.ledgerrelay.core.spi;
