package com.enterprise.cs.infra.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

/**
 * 访客密钥对装配：channel 侧签发与资源服务器侧校验共用同一密钥（单部署内单 bean）。
 * 密钥缺失时生成临时密钥对（WARN，重启失效——仅限 dev/sit；生产必须经 env 注入 PEM）。
 */
@Configuration
public class VisitorKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(VisitorKeyConfig.class);

    @Bean
    public VisitorKeys visitorKeys(VisitorAuthProperties props) {
        String pub = props.publicKeyPem();
        String priv = props.privateKeyPem();
        if ((pub == null || pub.isBlank()) && (priv == null || priv.isBlank())) {
            log.warn("cs.auth.visitor 公私钥未配置——生成临时密钥对（仅限 dev/sit；生产经 CS_AUTH_VISITOR_PUB_PEM/PRIV_PEM 注入；重启后已签发访客令牌全部失效）");
            KeyPair pair = generate();
            return new VisitorKeys(props.issuer(), pair.getPublic(), pair.getPrivate(), true);
        }
        Objects.requireNonNull(pub, "cs.auth.visitor.public-key-pem 与 private-key-pem 必须同时配置");
        Objects.requireNonNull(priv, "cs.auth.visitor.public-key-pem 与 private-key-pem 必须同时配置");
        return new VisitorKeys(props.issuer(),
                PemReader.readPublicKey(pub), PemReader.readPrivateKey(priv), false);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("generate ephemeral visitor keypair failed", e);
        }
    }

    /** 访客签发密钥与 issuer。ephemeral=true 表示临时密钥（dev/sit）。 */
    public record VisitorKeys(String issuer, PublicKey publicKey, PrivateKey privateKey, boolean ephemeral) {
    }
}
