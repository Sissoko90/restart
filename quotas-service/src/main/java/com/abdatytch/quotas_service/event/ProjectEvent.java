package com.abdatytch.quotas_service.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Événement projet publié par interface-service sur les topics
 * {@code project.created} et {@code project.deleted}.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} : l'émetteur peut enrichir
 * l'événement sans casser ce consommateur.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectEvent {

    private String projectId;

    private String userId;

    private String projectName;
}
