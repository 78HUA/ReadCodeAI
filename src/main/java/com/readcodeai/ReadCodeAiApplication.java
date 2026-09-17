package com.readcodeai;

import com.readcodeai.config.ReadCodeAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReadCodeAiProperties.class)
public class ReadCodeAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadCodeAiApplication.class, args);
    }
}
