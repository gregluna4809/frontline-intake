package com.frontline.model;

public record Resource(long id, Type type, String label, Status status) {
    public enum Type { PHONE, COMPUTER, STATION }
    public enum Status { FREE, OCCUPIED }
}
