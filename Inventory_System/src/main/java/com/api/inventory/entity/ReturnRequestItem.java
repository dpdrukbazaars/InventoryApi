
package com.api.inventory.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Column;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "return_request_items")
@IdClass(ReturnRequestItem.ReturnRequestItemKey.class)
public class ReturnRequestItem {

    // Primary Key 1
    @Id
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    // Primary Key 2
    @Id
    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false, length = 255)
    private String itemName;

    // Default Constructor
    public ReturnRequestItem() {
    }

    // Parameterized Constructor
    public ReturnRequestItem(
            Long orderItemId,
            Long requestId,
            Integer quantity,
            Long itemId,
            String itemName) {

        this.orderItemId = orderItemId;
        this.requestId = requestId;
        this.quantity = quantity;
        this.itemId = itemId;
        this.itemName = itemName;
    }

    // Getters and Setters

    public Long getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(Long orderItemId) {
        this.orderItemId = orderItemId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    // Composite Primary Key Class
    // Kept inside ReturnRequestItem.java
    public static class ReturnRequestItemKey
            implements Serializable {

        private static final long serialVersionUID = 1L;

        public Long orderItemId;
        public Long requestId;

        // Default Constructor
        public ReturnRequestItemKey() {
        }

        // Parameterized Constructor
        public ReturnRequestItemKey(
                Long orderItemId,
                Long requestId) {

            this.orderItemId = orderItemId;
            this.requestId = requestId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof ReturnRequestItemKey)) {
                return false;
            }

            ReturnRequestItemKey other =
                    (ReturnRequestItemKey) obj;

            return Objects.equals(
                    orderItemId, other.orderItemId)
                    && Objects.equals(
                    requestId, other.requestId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderItemId, requestId);
        }
    }
}
