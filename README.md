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
native-image是GraalVM非常亮眼且GraalVM团队宣传最多的cli工具，用于实现AOT。

据GraalVM项目发起人说明，AOT和JIT在底层代码上有80%-90%是重叠的(主要是Graal Compiler)。事实上，无论是JIT还是AOT，它们都是将字节码编译成机器代码，只不过JIT是在运行时监测热点方法并编译，而AOT是构建时将所有触达的方法都编译。从结果表现上来说，AOT方式运行的Java程序在启动时就达到性能峰值，而JIT方式运行的Java程序启动性能较差（需要经过warm up）,但随着时间不断的优化，最终性能会稍好于AOT运行方式。

native-image有以下优势：
1. java程序启动时间短。
2. 更少的内存占用。
3. 磁盘占用"小"。
这些优势使得native-image在微服务框架、云原生方面有更好的应用。

#### native-image demo
![](image/hello-world.png)



#### 一些关键命令行参数
native-image里有一些常用到的命令行参数, 例如:

`-Ob`加速构建过程（但编译优化较少，建议仅用在开发环境）。

`--static`、` -H:+StaticExecutableWithDynamicLibC`用于静态链接(默认动态链接)。静态链接即将依赖的一些机器代码（如c函数库libc）直接打包进应用程序里，这种方式将所有依赖都打包，移植性较好，但最总可执行文件磁盘占用大。而动态链接编译方式并不会直接将libc等库直接打包进应用程序里，而是留下类似指针的描述符，运行时按需加载，这使得最终可执行文件磁盘磁盘占用较小，且动态链接被加载到内存后可以由多个进程共享。
相较于静态链接，动态链接应用的更广泛，比如windows下.dll文件, mac下的.dylib文件, linux下.so文件，大部分都是动态链接库文件。而目前应用容器化、云原生呈主要趋势，当应用通过静态链接方式编译后（打包所有依赖），就可以将最终生成的可执行文件直接运行在更加轻量的image里（如alpine），这大大减少了image的磁盘占用大小，利于网络分发。

`--initialize-at-build-time`这个命令用于指定哪些类在构建时就进行类初始化。类初始化时会执行static代码块和一些static变量的直接初始化（实际也会被放进static代码快）。传统Java应用static代码块会在运行时才执行（类被加载并访问静态变量、静态方法等场景时执行），而native-image允许在构建的时候就执行static代码块，例如：
![](image/hello-world-initialization.png)
从上图可以看出，static代码块在构建时就被执行了，因而在运行时便不再执行。

在构建时初始化有好处也有坏处，具体可以查看这篇[文章](https://medium.com/graalvm/updates-on-class-initialization-in-graalvm-native-image-generation-c61faca461f7)：

好处：
1. 在构建时就将一些繁琐的工作做了，因此可以加速程序启动时间。
2. 假如有且仅有一个类的static代码块里依赖某个第三方库（例如借助fastjson解析.json配置文件），那么fastjson只会在构建时用到，最终AOT生成的可执行文件便完全不依赖fastjson了，这减少了可执行文件的磁盘占用。

坏处:
1. 会破坏用户期望的行为。例如获取CPU核数、获取当前系统时间等逻辑被放在了static代码块里, 那构建时初始化会使得程序在每次运行时都输出构建时的相应状态。

由于坏处较明显，GraalVM默认构建时不初始化任何类，由用户通过--initialize-at-build-time指定，但JDK内置类强制构建时初始化（例如创建枚举类实例），而用户代码或者框架代码也可以在META-INF/native-image下创建native-image.properties显式指定哪些类在构建时初始化:
![](image/netty-native-image-properties.png)

具体命令行参数可以通过 `native-image --help`, `native-image --expert-options`, `native-image --expert-options-all`查看。

#### limitations
native-image提前编译Java程序后，可以使Java程序启动快、内存占用小，那当然也得有一定的牺牲。native-image基于"closed-world-assumption"理念，它假定所有的代码在运行时是可知的，也就是说运行时不会加载新的代码，这也就意味着java的一些动态特性会被阉割：

例如javaagent, bytecode manipulation(动态字节码技术，如javassist，asm, cglib, byte-buddy)等是被指出不允许使用的。---所以可以曲线救国，比如在构建时生成动态代理类。

而对于反射、读取classpath下的资源文件、jdk动态代理、jni等动态特性提供了一定支持：
1. 构建时会根据代码自动分析哪些地方用到了这些动态特性。
2. 用户可以通过`元数据配置文件`显式指定哪些地方用到了这些动态特性（太麻烦，不建议）。
3. 提供[graalvm-reachability-metadata github仓库](https://github.com/oracle/graalvm-reachability-metadata)，让社区一同维护常用框架的`元数据配置文件`。
4. GraalVM内置tracing-agent代理工具, 执行命令（例如`java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image -jar target/XXX.jar`)后，会在运行时自动生成相应的`元数据配置文件`。
5. GraalVM通过社区合作，使一些微服务框架（例如Quarkus, Micronaut, Helidon, Spring Boot）内部支持native-image。

经过上面几个步骤，很多大型的Java程序也可以勉强跑起来了, 例如社区里有人用native-image将我的世界(minecraft)跑起来了, 具体参考[文章](https://medium.com/graalvm/native-minecraft-servers-with-graalvm-native-image-1a3f6a92eb48):
而minecraft用到的"元数据配置文件"也维护在[github仓库](https://github.com/hpi-swa/native-minecraft-server)上。

但我在尝试用native-image编译Flink应用程序时，卡在了Akka SPI的加载上（失败原因应该是flink-akka jar包里又嵌套jar包，native-image对这种情况可能暂不支持）。不过考虑到稳定性和峰值性能，对于Flink这种持续运行在后台且追求高吞吐的场景，建议还是以jit的方式运行。
#### native-maven-plugin
为了使native-image更加工程化，GraalVM提供了相应的maven、gradle插件，用户可以将native-image用到的各种命令行参数维护在pom.xml配置文件，并通过mvn生命周期或者执行goal的方式一键构建。

## Polyglot Programming
#### Truffle简单介绍

#### GraalPy Performance

#### GraalPy Access jar

#### Polyglot Programming in one java application

#### Java On Truffle



## Summary
期待GraalVM和OpenJDK一起发展

