package com.abdatytch.auth_service.entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** Accusé d'effacement d'un service pour une demande donnée. */
@Getter
@Setter
@Table("erasure_acknowledgements")
public class ErasureAcknowledgement extends BaseEntity {

    @Column("request_id")
    private String requestId;

    @Column("service_name")
    private String serviceName;

    @Column("completed_at")
    private LocalDateTime completedAt;

    /** Zéro est une réponse valable : le service ne détenait rien. */
    @Column("records_erased")
    private Integer recordsErased = 0;

    @Column("details")
    private String details;
}
