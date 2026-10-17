/**
 * ledger-relay의 도메인 규칙이다. 저장 상태, 전이표, 상태 기계, 순서 게이트, 내용 해시가 여기 있다.
 * 저장 방식과 상관없이 참인 규칙만 두며, 포트(spi)와 서비스(application)를 모른다.
 * 앱 코드는 이 패키지를 import하지 않는다. 이 패키지의 타입은 예고 없이 바뀔 수 있다.
 */
package io.github.ledgerrelay.core.domain;
