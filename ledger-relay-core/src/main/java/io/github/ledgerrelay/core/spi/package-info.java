/**
 * ledger-relay의 나가는 포트다. core가 바깥에 요구하는 것을 도메인 값으로 말한다.
 * jdbc(저장소, 트랜잭션), tigerbeetle(TB 클라이언트), core testFixtures(메모리 저장소, 가짜 TB)가 구현한다.
 * 운영용 이체 ID 생성기({@link TransferIdGenerator})는 ledger-relay-tigerbeetle의 {@code TigerBeetleTransferIds}, 워커
 * 이벤트({@link WorkerEvents})는 ledger-relay-spring-boot의 {@code LoggingWorkerEvents}가 구현한다.
 * 앱 코드는 이 패키지를 쓰지 않는다. 포트는 판단하지 않는다. 판단은 core.application이 한다.
 */
package io.github.ledgerrelay.core.spi;
