package com.readcodeai.retrieve.model;

/**
 * 符号种类的判断。
 *
 * <p>单独抽出来是因为**至少两处**要用同一个口径：问题路由（"实现类/成员"这类问题
 * 的目标必须是类型）与工具层的符号解析（模型给的 {@code Foo.bar} 里哪个是类型）。
 * 各写一份迟早会漂移。
 */
public final class SymbolKinds {

    private SymbolKinds() {
    }

    /** 是不是类型（类 / 接口 / 枚举 / 记录 / 注解）。 */
    public static boolean isType(String kind) {
        return switch (kind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION" -> true;
            default -> false;
        };
    }

    /** 是不是可调用的成员（方法 / 构造器）—— 只有它们才有调用边。 */
    public static boolean isCallable(String kind) {
        return "METHOD".equals(kind) || "CONSTRUCTOR".equals(kind);
    }
}
