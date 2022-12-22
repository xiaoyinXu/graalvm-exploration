package com.xxywebsite.graalvm;

public class JavaOnTruffleTest {
    public static void main(String[] args) {
        int durationMillis = 20000;
        long startTs = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTs <= durationMillis) {
            add0(3, 4);
        }
        System.out.println("waiting");
    }

    public static int add0(int num1, int num2) {
        return num1 + num2;
    }
}
