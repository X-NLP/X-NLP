package com.xnlp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application entry point for the X-NLP control plane and inference API. */
@SpringBootApplication
@EnableScheduling
public class XNLPApplication {

    public static void main(String[] args) {
        SpringApplication.run(XNLPApplication.class, args);
    }
}
