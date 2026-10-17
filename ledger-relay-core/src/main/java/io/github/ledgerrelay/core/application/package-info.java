/**
 * ledger-relay의 애플리케이션 서비스다. 포트(spi) 위에서 도메인 규칙(domain)을 실행한다.
 * 앱 진입(LedgerRelay)과 워커 단계(claim, 쓰기)가 여기 있다. 업무 판단은 이 패키지와 domain에만 둔다.
 * 앱 코드는 이 패키지를 import하지 않는다. Spring 모듈과 워커 루프가 쓴다.
 */
package io.github.ledgerrelay.core.application;
