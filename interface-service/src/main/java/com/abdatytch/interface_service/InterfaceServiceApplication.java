package com.abdatytch.interface_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InterfaceServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InterfaceServiceApplication.class, args);
	}

}
