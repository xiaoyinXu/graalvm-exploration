package com.xxywebsite.graalvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class PolyglotTest {
    public static void main(String[] args) {
        try (Context ctx = Context.create()) {
            Value pythonArr = ctx.eval("python", "[1, 2, 3]");
            ctx.getBindings("js").putMember("pythonArr", pythonArr);
            String jsSource = """
                    var sum = 0;
                    for (var i = 0; i < pythonArr.length; i++) {
                        sum += pythonArr[i];
                    }
                    sum; // 不需要return
                    """;
            System.out.println(ctx.eval("js", jsSource).asInt());
        }
    }
}
