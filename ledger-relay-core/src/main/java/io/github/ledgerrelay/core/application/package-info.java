/**
 * The application services of ledger-relay. They run the domain rules (domain) on top of the ports (spi).
 * The app entry point (LedgerRelay) and the worker steps (claim, writes) live here. Business decisions belong only in
 * this package and in domain. App code does not import this package. The Spring module and the worker loop use it.
 */
package io.github.ledgerrelay.core.application;
