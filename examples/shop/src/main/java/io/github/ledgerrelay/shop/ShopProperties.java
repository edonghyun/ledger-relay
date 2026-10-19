package io.github.ledgerrelay.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sample app settings. compose passes them as environment variables ({@code SHOP_TIGERBEETLE_ADDRESS} etc.).
 *
 * @param instanceName name of this instance shown in the Workers box of the UI
 * @param dashboardUrl the Grafana dashboard the UI links to
 */
@ConfigurationProperties("shop")
public record ShopProperties(@DefaultValue("shop") String instanceName, @DefaultValue Tigerbeetle tigerbeetle,
        @DefaultValue Demo demo, @DefaultValue("http://localhost:3300/d/ledger-relay") String dashboardUrl) {

    /** @param address {@code host:port}. A host name is resolved to IPv4 at startup */
    public record Tigerbeetle(@DefaultValue("127.0.0.1:3000") String address) {
    }

    /** Toxiproxy that the TB cut switch of the demo profile calls. */
    public record Demo(@DefaultValue("http://127.0.0.1:8474") String toxiproxyUrl,
            @DefaultValue("tigerbeetle") String toxiproxyProxy) {
    }
}
