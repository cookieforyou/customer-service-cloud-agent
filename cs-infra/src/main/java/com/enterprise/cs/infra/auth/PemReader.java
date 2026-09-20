package com.enterprise.cs.infra.auth;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** PEM（X.509 公钥 / PKCS#8 私钥）解析工具。 */
public final class PemReader {

    private PemReader() {
    }

    public static PublicKey readPublicKey(String pem) {
        try {
            byte[] der = decode(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid public key PEM", e);
        }
    }

    public static PrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = decode(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid private key PEM", e);
        }
    }

    private static byte[] decode(String pem, String type) {
        String body = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
