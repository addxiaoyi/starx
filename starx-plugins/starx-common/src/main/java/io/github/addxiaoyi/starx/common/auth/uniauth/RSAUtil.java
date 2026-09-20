/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth.uniauth;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

final class RSAUtil {
    private static final String ALGORITHM = "RSA";

    static String encryptByPublicKey(String data, String publicKeyStr) throws Exception {
        byte[] pubKey = Base64.getDecoder().decode(publicKeyStr);
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(pubKey);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        PublicKey publicKey = keyFactory.generatePublic(x509KeySpec);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(1, publicKey);
        byte[] encrypt = cipher.doFinal(data.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypt);
    }

    static String decryptByPublicKey(String data, String publicKeyStr) throws Exception {
        byte[] pubKey = Base64.getDecoder().decode(publicKeyStr);
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(pubKey);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        PublicKey publicKey = keyFactory.generatePublic(x509KeySpec);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(2, publicKey);
        byte[] decrypt = cipher.doFinal(Base64.getDecoder().decode(data));
        return new String(decrypt, "UTF-8");
    }

    static String decryptByPrivateKey(String data, String privateKeyStr) throws Exception {
        byte[] priKey = Base64.getDecoder().decode(privateKeyStr);
        PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(priKey);
        KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
        PrivateKey privateKey = keyFactory.generatePrivate(pkcs8KeySpec);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(2, privateKey);
        byte[] decrypt = cipher.doFinal(Base64.getDecoder().decode(data));
        return new String(decrypt, "UTF-8");
    }

    private RSAUtil() {
    }
}
