package com.cobre.challenge;

import com.cobre.challenge.adapter.in.messaging.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// WorkerProperties registered here (never bound anywhere - pre-existing gap from FEAT-006,
// surfaced only now that the app is actually booted end to end rather than via test slices).
@EnableConfigurationProperties(WorkerProperties.class)
@SpringBootApplication
public class ChallengeApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChallengeApplication.class, args);
	}

}
