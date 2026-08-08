package com.videoagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VideoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoAgentApplication.class, args);
    }
}
