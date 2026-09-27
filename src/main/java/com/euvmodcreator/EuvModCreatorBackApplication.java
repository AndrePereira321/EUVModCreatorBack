package com.euvmodcreator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EuvModCreatorBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(EuvModCreatorBackApplication.class, args);
    }

}
