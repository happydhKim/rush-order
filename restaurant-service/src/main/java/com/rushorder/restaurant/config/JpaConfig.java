package com.rushorder.restaurant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.rushorder.restaurant.repository")
public class JpaConfig {
}
