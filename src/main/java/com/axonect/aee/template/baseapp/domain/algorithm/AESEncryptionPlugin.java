package com.axonect.aee.template.baseapp.domain.algorithm;

import lombok.SneakyThrows;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class AESEncryptionPlugin implements EncryptionPlugin {

    @Override
    @SneakyThrows
    public String encrypt(String plainText, String algorithm, String secretKeyValue) {
        Cipher cipher = getCipher(algorithm);
        SecretKey secretKey = getSecretKey(algorithm, secretKeyValue);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedBytes = cipher.doFinal(plainText.getBytes());
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    @Override
    @SneakyThrows
    public String decrypt(String encryptedText, String algorithm, String secretKeyValue) {
        Cipher cipher = getCipher(algorithm);
        SecretKey secretKey = getSecretKey(algorithm, secretKeyValue);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
        return new String(decryptedBytes);
    }

    @Override
    @SuppressWarnings("java:S4790")
    @SneakyThrows
    public String hashMd5(String plainText) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hashBytes = md.digest(plainText.getBytes(StandardCharsets.UTF_8));

        // Convert bytes to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private SecretKey getSecretKey(String algorithm, String secretKeyValue) {
        return new SecretKeySpec(secretKeyValue.getBytes(), algorithm);
    }

    @SneakyThrows
    private Cipher getCipher(String algorithm) {
        return Cipher.getInstance(algorithm);
    }
}