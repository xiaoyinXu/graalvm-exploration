package com.xxywebsite.graalvm;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
public class BuildTimeInitializationTest {
    private static int processorsCount = Runtime.getRuntime().availableProcessors();
    private static LocalDateTime localDateTime;

    static {
        localDateTime = LocalDateTime.now();
    }

    public static void main(String[] args) throws Exception {
        System.out.println(processorsCount);
        System.out.println(String.format("ts:%s", localDateTime.toString()));
    }
}
