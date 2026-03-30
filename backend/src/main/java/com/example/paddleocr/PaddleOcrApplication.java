package com.example.paddleocr;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.paddleocr.mapper")
public class PaddleOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaddleOcrApplication.class, args);
    }
}
