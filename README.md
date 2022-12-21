# GraalVM Exploration

## 介绍
11月初的时候Spring Boot 3.0发布，其中有一个很吸引人的特性：aot（ahead ho time compilation），用户可以直接将Spring Boot应用提前编译成当前操作系统、CPU架构相关的能直接运行的机器代码。区别于传统Java应用jit(Just-In-Time compilation)的运行模式，aot使Java程序不再需要预热，大大提升了应用的启动速度，而Spring Boot 3.0 aot依赖的组件就是GraalVM里的native-image。

其实早在JDK9，OpenJDK就提供了jaotc命令支持，2016年由[JEP 295: Ahead-of-Time Compilation](https://openjdk.org/jeps/295)提出，而jaotc底层依赖的就是Graal Compiler的aot运行模式（Graal Compiler既支持jit也支持aot），但是由于其使用场景较少且维护成本大，2021年OpenJDK[JEP 410: Remove the Experimental AOT and JIT Compiler](https://openjdk.org/jeps/410)提出移除对Graal Compiler的支持，并指出想继续使用Graal Compiler的开发者可以转向GraalVM。

然而2022 10月25日 Oracle宣布将GraalVM贡献给Open JDK，意味着GraalVM会变得更"原生"，并跟随OpenJDK的节奏一起发展（每6月一个发行版本）。

那GraalVM是什么呢？GraalVM是一个完全由Java开发的[开源项目](https://github.com/oracle/graal)，根据官网的[介绍](https://www.graalvm.org/latest/docs/introduction/):
> GraalVM是一个高性能的JDK发型版本，它致力于加速JVM平台语言应用的执行，同时提供JavaScript, Python, Ruby, R, C/C++(需先转换成bitcode)的运行环境；GraalVM提供两种方式运行Java应用，JIT和AOT。GraalVM的使用户可以在一个应用里进行多语言编程（省去传统跨语言调用的开销）。

GraalVM架构图：TODO

GraalVM开源项目：TODO


GraalVM底层聚焦于编译技术(Graal Compiler), 在应用层面提供三个关键的特性/能力：
1. JIT优化。通过[JVMCI](https://openjdk.org/jeps/243)，将Graal Compiler作为hotspot C2的替代。
2. 支持AOT。提供native-image工具，
3. 支持Polyglot Programming。
本文主要对这三个特性进行简单的介绍和做一些DEMO演示。
   

## JIT优化
#### 什么是jit和warm up?
jit即just-in-time(即时编译), java源码首先被javac编译成字节码.class文件，当字节码被类加载器加载到hotspot里后，java方法首先会被解释器执行，当方法被执行到一定次数会被识别成热点方法，并由jit compiler直接编译成本地机器能够执行的native代码，从而加速方法的执行效率。

而 `类加载 -> 方法解释执行 -> 执行编译后的代码`这个过程可以认为是java程序的warm up时间，经过这个阶段后，java程序的性能才会达到峰值。

例如以下例子
```java
public class WarmUpTest {
    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            long startTs = System.currentTimeMillis();
            workLoad();
            long endTs = System.currentTimeMillis();
            System.out.println(String.format("耗时:%dms", endTs - startTs));
        }
    }

    private static void workLoad() {
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
        for (int i = 0; i < 10000; i++) {
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
```
运行结果打印如下：
![](image/warm-up-1.png)

而当我们加上jvm参数:`-Xint`, 该程序将不考虑jit,完全被解释执行：
![](image/warm-up-2.png)
可以看出，有jit的情况下，程序持续执行的速度更快，并且执行效率呈现从慢到快并趋于平稳的趋势。

hotspot里有两类jit compiler, 一个是client compiler(也被称作c1 compiler), 一个是server compiler(也被称作c2 compiler)。c1 compiler在编译方面优化较少但编译耗时短，c2 compiler在编译方面优化较多但耗时长，为了权衡两者，hotspot里默认是分层编译(tiered-compilation)，我们可以打印jvm默认参数进行验证。
![](image/tiered-compilation.png)
默认情况下，我们可以粗略的认为，当一个方法被执行到2000次时会被c1 compiler进行编译，被执行到15000次会被c2 compiler进行编译。

然而c2 compiler主要由C++编写，维护起来比较困难，最近几年jdk已经很少对其进行优化.
#### Graal Compiler
Graal Compiler完全由Java进行编写，没有历史包袱，根据Open JDK9提出的JVMCI规范(允许用java开发jit编译器)，Graal Compiler可以作为c2的替代品，在一些场景提供更多的优化。这里需要指出jit在将字节码翻译成机器代码前会做很多优化，例如函数内联(Inline)、逃逸分析(Escape Analysis)等，而这些优化在Graal Compiler里被抽象成Phase，目前Graal Compiler内置了大概62个Phase，其中27个授予专利。我们可以通过Idea查看：
![](image/phase.png)
据[GraalVM](https://www.graalvm.org/latest/reference-manual/java/compiler/)说明，Graal Compiler在一些经常用到Java高级特性（比如Stream、Lambdas）的应用表现得更好，而对IO密集型程序提升很小。

经过自己简单的测验，Graal Compiler相较于c2 compiler提升不是特别明显，甚至有些场景表现得还不如c2 compiler。但Graal Compiler的优势在于它是Java编写的，维护和拓展起来更加方便，还可以让用户很方便的进行调试(需要加上`-XX:-UseJVMCINativeLibrary`参数)。当使用Graal VM JDK时，通过`-XX:-UseJVMCICompiler`就可以使用原本的c2 compiler了。

## AOT-native image
#### 简单介绍

#### native-image cli简单演示

#### 一些关键命令行参数


#### limitations

#### 辅助工具
##### graalvm-reachability-metadata
##### tracing agent
##### 手动维护
##### native-maven-plugin




## Polyglot Programming
#### Truffle简单介绍

#### GraalPy Performance

#### GraalPy Access jar

#### Polyglot Programming in one java application

#### Java On Truffle



## Summary

