package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.event.UserCreatedEvent;
import com.abdatytch.auth_service.event.UserLoggedInEvent;
import com.abdatytch.auth_service.event.OAuth2ProfileCompletedEvent;

/**
 * Interface du service de publication d'événements Kafka
 */
public interface IEventPublisher {
    
    void publishUserCreated(UserCreatedEvent event);
    
    void publishUserLoggedIn(UserLoggedInEvent event);
    
    void publishOAuth2ProfileCompleted(OAuth2ProfileCompletedEvent event);
}
