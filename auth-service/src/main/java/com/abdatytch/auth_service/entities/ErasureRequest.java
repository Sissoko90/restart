package com.abdatytch.auth_service.entities;

import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Trace d'une demande d'effacement.
 *
 * <p>Ne contient <b>aucune donnée personnelle</b> : seulement l'identifiant
 * technique du porteur. Y conserver le nom ou l'adresse annulerait l'effacement
 * que cette table sert justement à prouver.
 */
@Getter
@Setter
@Table("erasure_requests")
public class ErasureRequest extends BaseEntity {

    @Column("subject_id")
    private String subjectId;

    @Column("reason")
    private ErasureReason reason;

    @Column("requested_at")
    private LocalDateTime requestedAt;

    /** Qui a déclenché : la personne elle-même, ou un administrateur. */
    @Column("requested_by")
    private String requestedBy;

    /** Renseigné quand tous les services attendus ont accusé réception. */
    @Column("completed_at")
    private LocalDateTime completedAt;
}
