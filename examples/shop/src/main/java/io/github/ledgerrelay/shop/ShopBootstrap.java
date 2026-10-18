package io.github.ledgerrelay.shop;

import com.tigerbeetle.Client;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Prepares the schema and the ledger accounts. Runs after all beans are created and before the worker loop
 * ({@code SmartLifecycle}) starts, so the worker never sees a DB without its tables.
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
