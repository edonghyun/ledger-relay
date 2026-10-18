package io.github.ledgerrelay.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 샘플 앱 설정. compose에서는 환경 변수({@code SHOP_TIGERBEETLE_ADDRESS} 등)로 넘긴다.
 *
 * @param instanceName 화면의 Workers 칸에 보일 이 인스턴스의 이름
 */
@ConfigurationProperties("shop")
public record ShopProperties(@DefaultValue("shop") String instanceName, @DefaultValue Tigerbeetle tigerbeetle,
        @DefaultValue Demo demo) {

    /** @param address {@code 호스트:포트}. 호스트 이름이면 기동 때 IPv4로 바꾼다 */
    public record Tigerbeetle(@DefaultValue("127.0.0.1:3000") String address) {
    }

    /** demo 프로필의 TB 끊기 스위치가 부르는 Toxiproxy. */
    public record Demo(@DefaultValue("http://127.0.0.1:8474") String toxiproxyUrl,
            @DefaultValue("tigerbeetle") String toxiproxyProxy) {
    }
}
