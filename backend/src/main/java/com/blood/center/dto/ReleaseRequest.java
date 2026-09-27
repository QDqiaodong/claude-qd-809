package com.blood.center.dto;

/**
 * 标本放行请求：必须指定复核人。
 */
public record ReleaseRequest(String reviewer) {
}
