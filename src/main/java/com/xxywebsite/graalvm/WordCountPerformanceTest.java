package com.xxywebsite.graalvm;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WordCountPerformanceTest {
    public static void main(String[] args) {
        int iteration = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        for (int i = 0; i < 10; i++) {
            long startTs = System.currentTimeMillis();
            workLoad(iteration);
            long endTs = System.currentTimeMillis();
            System.out.println(String.format("耗时:%dms", endTs - startTs));
        }
    }

    private static void workLoad(int iteration) {
        String text = "/*\n" +
                " * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.\n" +
                " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
                " *\n" +
                " * This code is free software; you can redistribute it and/or modify it\n" +
                " * under the terms of the GNU General Public License version 2 only, as\n" +
                " * published by the Free Software Foundation.  Oracle designates this\n" +
                " * particular file as subject to the \"Classpath\" exception as provided\n" +
                " * by Oracle in the LICENSE file that accompanied this code.\n" +
                " *\n" +
                " * This code is distributed in the hope that it will be useful, but WITHOUT\n" +
                " * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or\n" +
                " * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License\n" +
                " * version 2 for more details (a copy is included in the LICENSE file that\n" +
                " * accompanied this code).\n" +
                " *\n" +
                " * You should have received a copy of the GNU General Public License version\n" +
                " * 2 along with this work; if not, write to the Free Software Foundation,\n" +
                " * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.\n" +
                " *\n" +
                " * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA\n" +
                " * or visit www.oracle.com if you need additional information or have any\n" +
                " * questions.\n" +
                " */\n";
        for (int i = 0; i < iteration; i++) {
            toWordCount(text);
        }
    }

    private static Map<String, Long> toWordCount(String text) {
        return Stream
                .of(text.split("\\W+"))
                .map(String::toLowerCase)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
