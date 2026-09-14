package com.frontline.model;

public record Staff(long id, String displayName, Role role) {
    public enum Role { LSR, SUPERVISING_LSR, CLERK, CENTER_MANAGER, ADMINISTRATOR }
}
