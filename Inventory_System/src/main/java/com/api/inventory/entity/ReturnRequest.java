package com.api.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer asks to return items of a completed online order. Staff approve or decline it; when they record the
 * return (and the refund) for that order, the request is DONE.
 */
@Entity
@Table(name = "return_requests", indexes = {
        @Index(name = "idx_return_request_order", columnList = "orderId"),
        @Index(name = "idx_return_request_status", columnList = "status, createdAt")})
@Getter
@Setter
public class ReturnRequest {

    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    public static final String DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 120)
    private String userEmail;

    /** DAMAGED, WRONG_ITEM, CHANGED_MIND or OTHER (the same reasons as a return). */
    @Column(nullable = false, length = 20)
    private String reason;

    @Column(length = 500)
    private String details;

    @Column(nullable = false, length = 12)
    private String status = REQUESTED;

    /** Staff's answer to the customer (why declined, how to hand the items back). */
    @Column(length = 300)
    private String staffNote;

    @Column(length = 120)
    private String decidedBy;

    private Instant decidedAt;

    @Column(nullable = false)
    private Instant createdAt;


    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "return_request_items",
            joinColumns = @JoinColumn(name = "request_id"),
            foreignKey = @ForeignKey(
                    name = "fk_return_request_items_request"
            )
    )
    private List<Line> lines = new ArrayList<>();

    @Embeddable
    @Getter
    @Setter
    public static class Line {

        @Column(name = "order_item_id", nullable = false)
        private Long orderItemId;

        @Column(name = "item_id", nullable = false)
        private Long itemId;

        @Column(name = "item_name", nullable = false, length = 255)
        private String itemName;

        @Column(name = "quantity", nullable = false)
        private int quantity;

        public Line() {
        }

        public Line(
                Long orderItemId,
                Long itemId,
                String itemName,
                int quantity) {

            this.orderItemId = orderItemId;
            this.itemId = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
        }
    }

}
