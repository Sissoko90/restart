package com.abdatytch.setting_service.repositories;

import com.abdatytch.setting_service.entities.UserSetting;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserSettingRepository extends ReactiveCrudRepository<UserSetting, String> {

    Flux<UserSetting> findByUserId(String userId);

    Mono<UserSetting> findByUserIdAndSettingKey(String userId, String settingKey);

    Mono<Void> deleteByUserIdAndSettingKey(String userId, String settingKey);

    Mono<Void> deleteByUserId(String userId);
}
