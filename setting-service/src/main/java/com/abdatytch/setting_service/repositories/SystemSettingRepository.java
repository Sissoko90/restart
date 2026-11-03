package com.abdatytch.setting_service.repositories;

import com.abdatytch.setting_service.entities.SystemSetting;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSetting, String> {

    Mono<Void> deleteBySettingKey(String settingKey);
}
