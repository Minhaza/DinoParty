package com.dinoparty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DinoPartyApplication {

  public static void main(String[] args) {
    SpringApplication.run(DinoPartyApplication.class, args);
  }
}
