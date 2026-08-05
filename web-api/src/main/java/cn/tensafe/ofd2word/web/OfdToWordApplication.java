package cn.tensafe.ofd2word.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OfdToWordApplication {
    public static void main(String[] args) { SpringApplication.run(OfdToWordApplication.class, args); }
}

