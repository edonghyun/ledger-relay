/**
 * The domain rules of ledger-relay: stored states, the transition table, the state machine, the ordering gate and the
 * content hash. Only rules that hold regardless of storage live here, and it knows neither the ports (spi) nor the
 * services (application). App code does not import this package. Its types can change without notice.
 */
package io.github.ledgerrelay.core.domain;
