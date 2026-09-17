package com.readcodeai.index.model;

/** 继承 / 实现关系。父类型不在本仓库内时 {@code external=true}、{@code superSymbolKey} 为 null。 */
public record CollectedTypeRelation(
        String subSymbolKey,
        String superRaw,
        String superSymbolKey,
        String kind,
        boolean resolved,
        boolean external) {
}
