package com.enterprise.cs.channel.api;

import com.enterprise.cs.commons.constant.ErrorCodes;
import com.enterprise.cs.commons.exception.BusinessException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 身份 claims 提取（《11》§6/D-12 fail-closed）：tenantId 取 owner、principal 取 sub，
 * 缺失即拒（IDENTITY_INCOMPLETE）；客户端传入的租户标识一律忽略。
 */
public record AuthClaims(String tenantId, String principalId) {

    public static AuthClaims of(Jwt jwt) {
        String tenant = jwt.getClaimAsString("owner");
        String principal = jwt.getSubject();
        if (tenant == null || tenant.isBlank() || principal == null || principal.isBlank()) {
            throw BusinessException.of(ErrorCodes.IDENTITY_INCOMPLETE,
                    "identity claims incomplete: owner/sub required");
        }
        return new AuthClaims(tenant, principal);
    }
}
