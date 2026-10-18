package io.github.ledgerrelay.shop;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화면이 그리는 상품과 구매자. 가격을 화면 코드에 따로 두지 않는다. */
@RestController
class CatalogController {

    record CatalogView(List<Catalog.Product> products, List<Long> buyers) {
    }

    @GetMapping("/api/catalog")
    CatalogView catalog() {
        // 잔액을 비우는 테스트 전용 구매자(마지막)는 화면에 내지 않는다
        return new CatalogView(Catalog.all(), Accounts.BUYERS.subList(0, Accounts.BUYERS.size() - 1));
    }
}
