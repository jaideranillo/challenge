package com.cobre.challenge;

import org.springframework.boot.SpringApplication;

public class TestChallengeApplication {

	public static void main(String[] args) {
		SpringApplication.from(ChallengeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
