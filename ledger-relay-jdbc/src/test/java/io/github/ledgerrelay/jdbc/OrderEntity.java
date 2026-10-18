package io.github.ledgerrelay.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stand-in for an app JPA entity: one order. */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private String id;

    protected OrderEntity() {
    }

    public OrderEntity(String id) {
        this.id = id;
    }
}
