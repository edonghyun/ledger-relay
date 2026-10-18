package io.github.ledgerrelay.shop;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Products and buyers the UI renders. Prices are not duplicated in the UI code. */
@RestController
class CatalogController {

    record CatalogView(List<Catalog.Product> products, List<Long> buyers) {
    }

    @GetMapping("/api/catalog")
    CatalogView catalog() {
        // The last buyer is reserved for tests that drain the balance and is not shown in the UI
        return new CatalogView(Catalog.all(), Accounts.BUYERS.subList(0, Accounts.BUYERS.size() - 1));
    }
}
