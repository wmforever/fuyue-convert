package com.fuyue.formatconverter.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FormatConverterApplication {
    public static void main(String[] args) { SpringApplication.run(FormatConverterApplication.class, args); }
}

