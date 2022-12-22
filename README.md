# GraalVM探索

## GraalVM简介
11月初的时候Spring Boot发布3.0版本，其中有一个很亮眼的特性：aot（ahead-of-time compilation），用户可以直接将Spring Boot应用提前编译成能直接运行的机器代码。区别于传统Java应用jit(just-in-time compilation)的运行模式，aot使Java程序不再需要预热，大大提升了应用的启动速度，而Spring Boot 3.0 aot依赖的组件就是GraalVM里的native-image。

其实早在JDK9，OpenJDK就提供了jaotc命令，即对aot提供了有限支持。java的aot在2016年由[JEP 295: Ahead-of-Time Compilation](https://openjdk.org/jeps/295)提出，而jaotc底层依赖的就是Graal Compiler(也就是GraalVM的核心部分)，但是由于其使用场景较少且维护成本大，2021年OpenJDK[JEP 410: Remove the Experimental aot and jit Compiler](https://openjdk.org/jeps/410)提出移除对Graal Compiler的支持，并指出对aot感兴趣的开发者可以转向GraalVM。

然而2022 10月25日 Oracle又宣布将GraalVM贡献给Open JDK，这意味着GraalVM会跟随OpenJDK的节奏一起发展（每6月一个发行版本）。

那GraalVM是什么呢？GraalVM是一个完全由Java开发的[开源项目](https://github.com/oracle/graal)，同时也是JDK的一种发行版本。根据官网的[介绍](https://www.graalvm.org/latest/docs/introduction/):
> GraalVM是一个高性能的JDK发型版本，它致力于加速JVM平台语言应用的执行，同时提供JavaScript，Python，Ruby，R，C/C++(需先转换成bitcode)的运行环境；GraalVM提供两种方式运行Java应用，jit和aot。GraalVM的使用户可以在一个应用里进行多语言编程（省去传统跨语言调用的开销）。

GraalVM架构图：
![](https://bj.bcebos.com/cookie/graalvm_architecture_community.png)

[GraalVM开源项目](https://www.graalvm.org/community/opensource/)：
![](https://bj.bcebos.com/cookie/graalvm-open-source.png)

GraalVM在应用层面提供三个关键的特性/能力：

1. 对已有的HotSpot jit(just-in-time compilation，即时编译)进行优化。
2. 支持aot(ahead-of-time compilation，提前编译)。
3. 提供动态语言实现框架（Truffle）和支持多语言编程(Polyglot Programming)。
   
GraalVM底层聚焦的主要是编译技术，本文首先会谈一谈"编译"和"解释"的区别，"即时编译"和"提前编译"的区别，再主要讲讲GraalVM的三个主要特性。
## 编译和解释
按照我自己的理解：

编译：将"原始码"`整体`转换成"目标码"的过程。

解释：将"原始码"`逐条`转换成"目标码"的过程。

"原始码"并不一定是编程语言源代码，而"目标码"也并不一定是可以直接执行的机器码，编译和解释的区别主要在`整体`和`逐条`上。

常见的编译型语言有C、C++、Golang、Rust等，它们的源代码在编译期间被`整体`转换成可以直接执行的机器码；常见的解释型语言有Python、Javascript，它们的源代码在运行期间被逐条解释执行。而Java严格意义上属于半编译半解释型语言，且它有多个编译阶段。首先Java源码通过javac被`整体`转换成字节码，这是第一阶段的编译。而在运行期间，当字节码被类加载器加载到虚拟机时，非热点方法的方法体的所有字节码指令(操作码 + 数据)会被`逐条`解释执行，而热点方法的整个方法体会被`整体`编译成机器码，这是第二阶段的编译。

(纯)编译型语言特点：
`优点`：
1. 执行速度快。例如C/C++等编程语言，在运行时源码已经完全转换成可以直接执行的机器码了。

`缺点`：
1. 灵活性差。一旦涉及到源码的改动，需要重新编译，而编译时间（尤其是大型项目）一般都是很漫长的。
2. 可移植性/跨平台性差。例如C/C++等编程语言，源码编译后会被完全转换成和当前操作系统、CPU架构相关的机器码，而这个机器码一般不能移植到不同架构的平台的。所以一般对此的解决方案是cross-compilation(交叉编译)，即在一个平台上就能编译生成多个平台版本的机器码。
3. 三方库使用起来比较麻烦。(编译结果和CPU系统、CPU架构耦合，且需要考虑动态链接/静态链接等)。

解释型语言特点：

`优点`：
1. 可移植性/跨平台型好。
2. 具有很多的动态特性，如动态类型，反射等。

`缺点`：
1. 执行速度慢。

## 即时编译(jit)和提前编译(aot)
即时编译(jit)即just-in-time compilation，编译发生在运行时。

提前编译(aot)即ahead-of-time compilation，编译发生在构建时。

jit和aot本质上并没有什么不同，都是编译，都是将"原始码"整体转换成"目标码"，区别主要在发生的时机。当然啦，aot是一锤子买卖，也就意味着构建时需要做更多的分析。而jit一般伴随着后台监控线程，可以持续地优化关键的热点方法。

常见的编译型语言的编译一般都是aot，优点（速度快）和缺点（灵活性、跨平台性差）都很明显，于是有了[llvm](https://github.com/llvm/llvm-project)项目，简单来说llvm抽象出了与具体编程语言和具体平台独立的中间形式IR（Intermediate Representation），llvm首先将"源码" 编译成LLVM bitcode，而LLVM bitcode可以被`lli` jit方式执行，也可以被`llc`aot编译成机器码。llvm通过引入IR对编译阶段解耦，使不同编程语言和不同平台的编译结果可以互相复用，且兼具了传统"编译性语言"的执行速度快和传统"解释型语言"的跨平台性，这和JVM的设计非常相似。

而一般我们聊jit，大家想到的都是Java HotSpot虚拟机里的jit编译器，但实际JavaScript的V8、和一些Python实现(如PyPy)的虚拟机里也都有jit的存在，jit compiler一般会伴随解释器，目的也很明确，就是为了弥补解释执行速度慢的问题。jit相较于aot，一般会有预热阶段(warm up)，例如Java在云原生场景经常被诟病启动速度慢（当然还有内存占用大），但随着jit后台持续不断的监测和优化，最终Java程序的峰值性能也是很可观的（一般还是慢于jit模式）。

传统Java应用的运行方式都是解释器 + jit compiler，GraalVM的出现，使得Java应用在aot方向也慢慢开始发展，同时GraalVM使多语言混合编程也变成了一种可能。

## HotSpot jit Compiler和Graal jit Compiler
#### 什么是jit和warm up?
jit即just-in-time(即时编译)，java源码首先被javac编译成字节码.class文件，当字节码被类加载器加载到hotspot里后，java方法首先会被解释器执行，当方法被执行到一定次数会被识别成热点方法，并由jit compiler直接编译成本地机器能够执行的native代码，从而加速方法的执行效率。

而 `类加载 -> 方法解释执行 -> 执行编译后的代码`这个过程可以认为是java程序的warm up时间，经过这个阶段后，java程序的性能才会达到峰值。

例如以下例子：
```java
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
```

运行结果打印如下：
![](https://bj.bcebos.com/cookie/word-count-jit.png)

而当我们加上jvm参数:`-Xint`，该程序将不使用jit，完全被解释执行：
![](https://bj.bcebos.com/cookie/word-count-interpreter.png)
可以看出，有jit的情况下，程序持续执行的速度更快，并且执行效率呈现从慢到快并趋于平稳的趋势。

hotspot里有两类jit compiler，一个是client compiler(也被称作c1 compiler)，一个是server compiler(也被称作c2 compiler)。c1 compiler在编译方面优化较少但编译耗时短，c2 compiler在编译方面优化较多但耗时长，为了权衡两者，hotspot里默认是分层编译(tiered-compilation)，我们可以打印jvm默认参数进行验证。
![](https://bj.bcebos.com/cookie/tiered-compilation.png)
默认情况下，我们可以粗略的认为，当一个方法被执行到2000次时会被c1 compiler进行编译，被执行到15000次会被c2 compiler进行编译。

然而c2 compiler主要由C++编写，维护起来比较困难，最近几年jdk已经很少对其进行优化。

#### Graal Jit Compiler有什么优势？
Graal Compiler完全由Java进行编写，没有历史包袱，根据Open JDK9提出的[JVMCI](https://openjdk.org/jeps/243)规范(允许用java开发jit编译器)，Graal Compiler可以作为c2的替代品，在一些场景提供更多的优化。这里需要指出jit在将字节码翻译成机器代码前会做很多优化，例如函数内联(Inline)、逃逸分析(Escape Analysis)等，而这些优化在Graal Compiler里被抽象成Phase，目前Graal Compiler内置了大概62个Phase，其中27个授予专利。我们可以通过Idea查看：
![](https://bj.bcebos.com/cookie/phase.png)
根据[GraalVM](https://www.graalvm.org/latest/reference-manual/java/compiler/)说明，Graal Compiler在一些经常用到Java高级特性（比如Stream、Lambdas）的应用表现得更好，而对IO密集型程序提升很小。

经过自己简单的测验，Graal Compiler相较于c2 compiler提升不是特别明显，甚至有些场景表现得还不如c2 compiler。但Graal Compiler的优势在于它是Java编写的，维护和拓展起来更加方便，还可以让用户很方便的进行调试(需要加上`-XX:-UseJVMCINativeLibrary`参数)。当使用Graal VM JDK时，通过`-XX:-UseJVMCICompiler`就可以使用原本的c2 compiler了。

## aot和native-image
#### 什么是native-image
native-image是GraalVM非常亮眼且GraalVM团队宣传最多的cli工具，用于实现aot。

据GraalVM项目发起人说明，aot和jit在底层代码上有80%-90%是重叠的(主要是Graal Compiler)。事实上，无论是jit还是aot，它们都是将字节码编译成机器代码，只不过jit是在运行时监测热点方法并编译，而aot是构建时将所有触达的方法都编译。从结果表现上来说，aot方式运行的Java程序在启动时就达到性能峰值，而jit方式运行的Java程序启动性能较差（需要经过warm up），但随着时间不断的优化，最终性能会稍好于aot运行方式。

native-image有以下特点：

`优点`
1. java程序启动时间短。
2. 更少的内存占用。
3. 磁盘占用"小"（构建出来的可执行文件不依赖JDK）。 这些优势使得native-image在微服务框架、云原生方面有更好的应用。

`不足`
1. 构建时间长（需要花很多时间分析和编译）。
2. 对Java的很多动态特性支持的不是很好。
3. 很多Java组件在使用native-image构建时会遇到很多阻碍。

#### native-image简单用例

![](https://bj.bcebos.com/cookie/hello-world.png)

![](https://bj.bcebos.com/cookie/spring-boot-3-demo.png)
上图是Spring Boot 3.0的一个demo，可以看出来使用native-image，启动时间从1.298秒变成了0.047秒。

#### 一些关键命令行参数

native-image里有一些常用到的命令行参数，例如:

`-Ob`加速构建过程（但编译优化较少，建议仅用在开发环境）。

`--gc=`选择垃圾回收器，目前仅支持epsilon(不回收)、serial、G1。

`--static`、` -H:+StaticExecutableWithDynamicLibC`用于静态链接(默认动态链接)。

静态链接即将依赖的一些机器代码（如c函数库libc）直接打包进应用程序里，这种方式将所有依赖都打包，移植性较好，但最总可执行文件磁盘占用大。而动态链接编译方式并不会直接将libc等库直接打包进应用程序里，而是留下类似指针的描述符，运行时按需加载，这使得最终可执行文件磁盘磁盘占用较小，且动态链接被加载到内存后可以由多个进程共享。

相较于静态链接，动态链接应用的更广泛，比如windows下.dll文件，mac下的.dylib文件，linux下.so文件，大部分都是动态链接库文件。而目前应用容器化、云原生呈主要趋势，当应用通过静态链接方式编译后（打包所有依赖），就可以将最终生成的可执行文件直接运行在更加轻量的image里（如alpine），这大大减少了image的磁盘占用大小，利于网络分发。

`--initialize-at-build-time`
这个命令用于指定哪些类在构建时就进行类初始化。类初始化时会执行static代码块和一些static变量的直接初始化（实际也会被放进static代码快）。传统Java应用static代码块会在运行时才执行（类被加载并访问静态变量、静态方法等场景时执行），而native-image允许在构建的时候就执行static代码块，例如：
![](https://bj.bcebos.com/cookie/hello-world-initialization.png)
从上图可以看出，static代码块在构建时就被执行了，因而在运行时便不再执行。

在构建时初始化有优点也有缺点，具体可以查看这篇[文章](https://medium.com/graalvm/updates-on-class-initialization-in-graalvm-native-image-generation-c61faca461f7)：

`优点`：
1. 在构建时就将一些繁琐的工作做了，因此可以加速程序启动时间。
2. 假如有且仅有一个类的static代码块里依赖某个第三方库（例如借助fastjson解析.json配置文件），那么fastjson只会在构建时用到，最终aot生成的可执行文件便完全不依赖fastjson了，这减少了可执行文件的磁盘占用。

`缺点`:
1. 会破坏用户期望的行为。例如获取CPU核数、获取当前系统时间等逻辑被放在了static代码块里，那构建时初始化会使得程序在每次运行时都输出构建时的相应状态。

由于破坏预期行为是用户不能接受的，GraalVM在最新的版本默认构建时不初始化任何类，由用户通过--initialize-at-build-time指定，但JDK内置类强制构建时初始化（例如创建枚举类实例），而用户代码或者框架代码也可以在META-INF/native-image下创建native-image.properties显式指定哪些类在构建时初始化:
![](https://bj.bcebos.com/cookie/netty-native-image-properties.png)

具体命令行参数可以通过 `native-image --help`，`native-image --expert-options`，`native-image --expert-options-all`查看。

#### native-image有哪些限制？

native-image提前编译Java程序后，可以使Java程序启动快、内存占用小，那当然也得有一定的牺牲。native-image基于"closed-world-assumption"理念，它假定所有的代码在运行时是可知的，也就是说运行时不会加载新的代码，这也就意味着java的一些动态特性会被阉割：

例如javaagent，bytecode manipulation(动态字节码技术，如javassist，asm，cglib，byte-buddy)等是被指出不允许使用的。---所以可以曲线救国，比如在构建时生成动态代理类。

而对于反射、读取classpath下的资源文件、jdk动态代理、jni等动态特性提供了一定支持：

1. 构建时会根据代码自动分析哪些地方用到了这些动态特性。
2. 用户可以通过`元数据配置文件`显式指定哪些地方用到了这些动态特性（太麻烦，不建议）。
3. 提供[graalvm-reachability-metadata github仓库](https://github.com/oracle/graalvm-reachability-metadata)，让社区一同维护常用框架的`元数据配置文件`。
4. GraalVM内置tracing-agent代理工具，执行命令（例如`java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image -jar target/XXX.jar`)后，会在运行时自动生成相应的`元数据配置文件`。
5. GraalVM通过社区合作，使一些微服务框架（例如Quarkus，Micronaut，Helidon，Spring Boot）内部支持native-image。

经过上面几个步骤，很多大型的Java程序也可以勉强跑起来了，例如社区里有人用native-image将我的世界(minecraft)跑起来了，具体参考[文章](https://medium.com/graalvm/native-minecraft-servers-with-graalvm-native-image-1a3f6a92eb48)，而minecraft用到的"元数据配置文件"也维护在[github仓库](https://github.com/hpi-swa/native-minecraft-server)上。

但我在尝试用native-image编译Flink应用程序时，卡在了Akka SPI的加载上（失败原因应该是flink-akka jar包里又嵌套jar包，native-image对这种情况可能暂不支持）。

不过考虑到稳定性和峰值性能，对于Flink这种持续运行在后台且追求高吞吐的场景，建议还是以jit的方式运行。

#### maven插件
为了使native-image更加工程化，GraalVM提供了相应的gradle、[maven插件](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html)，用户可以将native-image用到的各种命令行参数维护在pom.xml配置文件，并通过mvn生命周期或者执行goal的方式一键构建，如图：
![](https://bj.bcebos.com/cookie/native-maven-plugin.png)

#### 性能优化-PGO
native-image可以借助PGO(Profile-Guided Optimizations)进一步优化性能。具体可以参考[文章](https://www.graalvm.org/latest/reference-manual/native-image/guides/optimize-native-executable-with-pgo/)。
大致步骤就是先通过native-image生成初版的可执行文件，运行该可执行文件生成profiling文件，并基于该profiling文件再次构建生成性能更好的可执行文件，以上步骤可以反复迭代。示例如下：
![](https://bj.bcebos.com/cookie/word-count-benchmark.png)
如上图，上中下分别是：传统jit运行模式、native-image aot运行模式、经过native image pgo优化后的aot运行模式，经过pgo优化后，性能会提升一些，但还是稍差于jit运行模式。


#### 监控
native-image生成的可执行程序可以借助jvisualvm工具进行监控，前提是用native构建的时候需要加上`--enable-monitoring=`参数。
![](https://bj.bcebos.com/cookie/enable-monitoring.png)
![](https://bj.bcebos.com/cookie/native-image-jvisualvm.png)

## Truffle和多语言编程
#### 什么是Truffle?
[Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/)是一个开源的实现动态编程语言的框架，它可以使用户实现的编程语言高效的运行在GraalVM上。

使用Truffle，用户只需关注构建抽象语法树（AST）和具体树节点的定义和执行逻辑，而Truffle专注于提升编程语言性能(借助Truffle Compiler和Graal Compiler)和提供与其它编程语言的交互能力。

目前GraalVM基于Truffle实现了[Python](https://github.com/oracle/graalpython)，[JavaScript](https://github.com/oracle/graaljs)，[Ruby](https://github.com/oracle/truffleruby/)，[R](https://github.com/oracle/fastr)，和支持运行能够被编译成LLVM bitcode的语言，如C/C++。

#### GraalPy性能表现
GraalPy是由Truffle实现的Python，目前支持Python 3.8版本，还处于试验阶段，比较致命的是支持的第三方库比较少。对于纯Python编写的程序（不依赖C/C++函数库）时，其速度远快于CPython，例如以下代码：
```python
import time

def add(num1, num2):
    return num1 + num2

start_ts = time.time()
for i in range(10000):
    for j in range(10000):
        add(i, j)
end_ts = time.time()
print("it costs {} s".format(end_ts - start_ts))
```

![](https://bj.bcebos.com/cookie/performance-python.png)
由上图可以看出，graalpy比cpython的执行速度大概快了10倍。

#### GraalPy访问jar包
由Truffle实现的编程语言可以很容易与Java生态打通，例如GraalPy可以借助Polyglot API调用第三方jar包，需指定classpath：
![](https://bj.bcebos.com/cookie/graalpy-access-jar.png)

#### 跨语言调用
比较常见的应用场景是Java作为Host Language，其它语言作为Guest Language，实现多语言编程（运行在同一个进程里）：
![](https://bj.bcebos.com/cookie/polyglot-java-example.png)
上述代码由Java编写，主要用到了Polyglot API。首先在"python"里创建了一个数组"[1，2，3]"，再将这个数组绑定到了"javascript"的上下文里，再由javascript去访问这个数组进行一个求和。从这个例子我们可以感受到GraalVM可以很方便地完成多语言的数据共享，并基于[Truffle Polyglot Interop Protocol](https://www.graalvm.org/22.2/reference-manual/java-on-truffle/interoperability/)保证了对象操作的安全性：上面由Python创建的数组，在js通过调用`pythonArr.length`时，实际会委托给对象的创建语言去执行，也就是调用python方法`len`。

#### Java On Truffle
GraalVM最近用Truffle实现了Java本身，即[Java On Truffle](https://www.graalvm.org/latest/reference-manual/java-on-truffle/)，目前也处于试验阶段，不建议生产使用，感兴趣的同学可以去看看介绍。

## 总结
GraalVM在应用层面存在三个关键能力：
一、利用JVMCI将HotSpot c2 Compiler替换成Graal Compiler。
`优点`：
1. 由于JVMCI是可插拔式的，仅仅通过JVM参数就能开启和关闭，原有的Java应用程序通过"零"迁移成本就可能表现出更好的性能。 
2. Graal Compiler由Java编写，更容易维护、拓展和调试。

`不足`:
1. 目前GraalVM社区版和企业版不支持JDK8，支持JDK11、JDK17、JDK19。
   

二、提供native-image可以将字节码提前编译成可执行程序，并提供大量命令行参数和工具供用户调优和监控。

`优点`：
1. 省去了java应用冷启动/warm up时间，刚运行就能达到性能峰值。 
2. 内存消耗更低。
3. 可执行文件不再依赖JDK，磁盘占用更低，利于压缩容器镜像大小。
4. 鉴于以上优点，各种微服务框架（如Quarkus，Micronaut，Helidon，Spring Boot）和云原生场景慢慢开始拥抱native-image。

`不足`：
1. 不支持javaagent、bytecode manipulation（动态字节码技术，如javassist，asm，cglib, byte-buddy, 如想要生成动态代理可考虑在构建时生成）。
2. 反射、读取classpath下的资源文件、jni、jdk动态代理等动态特性需要手动通过配置文件或借助trace agent等工具来实现，总体来说还是比较麻烦。
3. 对于后台长期运行且追求高吞吐的Java应用，aot模式的峰值性能很多时候不如jit模式。
4. 很多常用Java的框架、引擎、存储和native-image支持的还不是很好，如log4j2、mybatis、Flink、Elasticsearch。

三、提供Truffle动态语言实现框架和让多种编程语言能够运行在GraalVM平台上，提升语言性能的同时并支持多语言编程。

`优点`：
1. GraalVM团队有专门的人去维护Python、JavaScript、Ruby，R等语言。
2. 借助Truffle实现的语言运行在GraalVM时，可以自动享受Truffle Compiler、Graal Compiler等jit编译，从而得到性能提升，并且可以借助Polyglot API进行多语言混合编程。

`不足`：
1. GraalPy还在起步阶段，在三方库（尤其是依赖C/C++）上支持的不是很好。
2. Polyglot API能满足简单的跨语言调用，但对于复杂场景和模块化的支持不是很足。

GraalVM出现在大众视野中还没有多久，很多地方还在不断优化，目前Oracle已计划将GraalVM社区版本代码贡献给OpenJDK，期待GraalVM和Java的发展～
