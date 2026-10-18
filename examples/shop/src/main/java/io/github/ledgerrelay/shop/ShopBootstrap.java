package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 스키마와 원장 계좌를 준비한다. 빈을 모두 만든 뒤, 워커 루프({@code SmartLifecycle})가 시작하기 전에 돈다. 그래서 워커는 표가
 * 없는 DB를 보지 않는다.
 */
@Component
class ShopBootstrap implements SmartInitializingSingleton {

    private final DataSource dataSource;
    private final Client client;

    ShopBootstrap(DataSource dataSource, Client client) {
        this.dataSource = dataSource;
        this.client = client;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ShopSchema.apply(dataSource);
        Accounts.ensure(client);
    }
}
