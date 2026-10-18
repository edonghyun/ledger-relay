/**
 * PostgreSQL storage adapter. Implements the outgoing ports of core ({@code CommandStore}, {@code Transactions}) with
 * Spring JDBC. It makes no decisions; the core services make them. The schema ships as {@code schema.sql} and is not
 * applied automatically.
 */
package io.github.ledgerrelay.jdbc;
