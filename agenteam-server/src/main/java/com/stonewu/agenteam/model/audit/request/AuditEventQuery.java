package com.stonewu.agenteam.model.audit.request;

/**
 * 操作审计的明确筛选，不接受字段名称或查询表达式。
 */
public record AuditEventQuery(String from, String to, String actorUserId, String action, String objectType,
                             String result) {
}
