package com.axonect.aee.template.baseapp.domain.algorithm;

public interface EncryptionPlugin {
    String encrypt(String plainText, String algorithm, String secretKeyValue);
    String decrypt(String encryptedText, String algorithm, String secretKeyValue);
    String hashMd5(String plainText); // Add this method
}