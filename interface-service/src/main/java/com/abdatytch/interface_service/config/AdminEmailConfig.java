package com.abdatytch.interface_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class AdminEmailConfig {

    @Value("${admin.mail.host}")
    private String host;

    @Value("${admin.mail.port}")
    private int port;

    @Value("${admin.mail.username}")
    private String username;

    @Value("${admin.mail.password}")
    private String password;

    @Value("${admin.mail.from}")
    private String from;

    @Value("${admin.critical.mail.host}")
    private String criticalHost;

    @Value("${admin.critical.mail.port}")
    private int criticalPort;

    @Value("${admin.critical.mail.username}")
    private String criticalUsername;

    @Value("${admin.critical.mail.password}")
    private String criticalPassword;

    @Value("${admin.critical.mail.from}")
    private String criticalFrom;

    @Bean
    @Primary
    @ConditionalOnProperty(name = "admin.mail.enabled", havingValue = "true")
    public JavaMailSender adminMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "3000");
        props.put("mail.smtp.writetimeout", "5000");

        return mailSender;
    }

    @Bean
    @ConditionalOnProperty(name = "admin.critical.mail.enabled", havingValue = "true")
    public JavaMailSender criticalMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(criticalHost);
        mailSender.setPort(criticalPort);
        mailSender.setUsername(criticalUsername);
        mailSender.setPassword(criticalPassword);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "3000");
        props.put("mail.smtp.writetimeout", "5000");

        return mailSender;
    }
}
