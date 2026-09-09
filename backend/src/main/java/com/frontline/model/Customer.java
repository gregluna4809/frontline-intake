package com.frontline.model;

public record Customer(long id, String displayName, Language language, boolean veteran) {
    public enum Language { EN, ES, OTHER }
}
