package com.axonect.aee.template.baseapp.domain.enums;

public enum EncryptionMethod {
    PLAIN(0),
    MD5(1),
    CSG_ADL(2);

    private final int code;

    EncryptionMethod(int code) { this.code = code; }

    public int getCode() { return code; }

    public static EncryptionMethod fromCode(int code) {
        for (EncryptionMethod e : values()) {
            if (e.code == code) return e;
        }
        throw new IllegalArgumentException("Invalid encryption code: " + code);
    }
}