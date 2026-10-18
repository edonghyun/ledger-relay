package io.github.ledgerrelay.shop;

import java.util.List;
import java.util.Optional;

/** Products for sale. Stock is a non-goal and is not tracked. Prices are in satang (1/100 baht). */
public final class Catalog {

    public record Product(String code, String name, String emoji, long priceSatang) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product("earbuds", "Wireless earbuds", "🎧", 149_000),
            new Product("keyboard", "Mechanical keyboard", "⌨️", 289_000),
            new Product("mouse", "Wireless mouse", "🖱️", 89_000));

    private Catalog() {
    }

    public static List<Product> all() {
        return PRODUCTS;
    }

    public static Optional<Product> find(String code) {
        return PRODUCTS.stream().filter(product -> product.code().equals(code)).findFirst();
    }
}
