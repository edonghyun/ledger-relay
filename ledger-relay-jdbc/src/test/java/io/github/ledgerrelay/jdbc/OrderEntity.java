package io.github.ledgerrelay.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 앱의 JPA 엔터티 대역: 주문 하나. */
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
