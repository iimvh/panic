package com.meetingroom;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.meetingroom")
public class MeetingRoomApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(MeetingRoomApplication.class);
		app.addInitializers(new DotenvApplicationInitializer());
		app.run(args);
	}
}
