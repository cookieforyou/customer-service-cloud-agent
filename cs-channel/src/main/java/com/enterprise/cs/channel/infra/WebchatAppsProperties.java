package com.enterprise.cs.channel.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** webchat 站点应用配置（《08》§2：appKey+secret+租户映射；M0批3 为 yml 静态表，M3 多租户入 cs_tenant_config）。 */
@ConfigurationProperties("cs.channel.webchat")
public record WebchatAppsProperties(List<WebchatApp> apps, Duration signWindow) {

    public WebchatAppsProperties {
        if (signWindow == null) {
            signWindow = Duration.ofMinutes(5);
        }
        if (apps == null) {
            apps = List.of();
        }
    }

    public record WebchatApp(String appKey, String secret, String tenantId) {
    }

    public Optional<WebchatApp> find(String appKey) {
        return apps.stream().filter(a -> a.appKey().equals(appKey)).findFirst();
    }
}
