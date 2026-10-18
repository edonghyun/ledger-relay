package io.github.ledgerrelay.shop;

import java.util.List;
import java.util.Optional;

/** 파는 상품. 재고는 비목표라 다루지 않는다. 가격은 사탕(1/100 바트) 단위다. */
public final class Catalog {

    public record Product(String code, String name, String emoji, long priceSatang) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product("earbuds", "무선 이어폰", "🎧", 149_000),
            new Product("keyboard", "기계식 키보드", "⌨️", 289_000),
            new Product("mouse", "무선 마우스", "🖱️", 89_000));

    private Catalog() {
    }

    public static List<Product> all() {
        return PRODUCTS;
    }

    public static Optional<Product> find(String code) {
        return PRODUCTS.stream().filter(product -> product.code().equals(code)).findFirst();
    }
}
