package com.abdatytch.interface_service.exception;

/**
 * Exception levée quand l'utilisateur atteint sa limite de projets
 */
public class ProjectLimitExceededException extends BusinessException {

    public ProjectLimitExceededException(int maxProjects) {
        super("Limite de projets atteinte. Maximum autorisé: " + maxProjects, "PROJECT_LIMIT_EXCEEDED");
    }

    public ProjectLimitExceededException(String message) {
        super(message, "PROJECT_LIMIT_EXCEEDED");
    }
}
