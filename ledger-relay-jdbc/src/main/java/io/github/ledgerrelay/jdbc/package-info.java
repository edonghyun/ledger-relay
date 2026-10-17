/**
 * PostgreSQL 저장소 어댑터. core의 나가는 포트({@code CommandStore}, {@code Transactions})를 Spring JDBC로 구현한다.
 * 판단은 하지 않는다. 판단은 core의 서비스가 한다(02a P2a-16). 스키마는 {@code schema.sql}로 제공하며 자동 적용하지
 * 않는다(스펙 §5.6).
 */
package io.github.ledgerrelay.jdbc;
