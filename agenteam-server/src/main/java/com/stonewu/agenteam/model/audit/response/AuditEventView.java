package com.stonewu.agenteam.model.audit.response;

import com.stonewu.agenteam.model.audit.entity.AuditEventRow;

import java.time.Instant;

/**
 * 返回给管理端的操作审计记录；不直接暴露 {@code detail_redacted_json} 之外的内部字段。
 */
public record AuditEventView(String id, String enterpriseId, String actorUserId, String actorName, String action,
                            String objectType, String objectId, String result, String summary, Instant createdAt) {

    public static AuditEventView from(AuditEventRow row) {
        return new AuditEventView(row.getId(), row.getEnterpriseId(), row.getActorUserId(), row.getActorName(),
            row.getAction(), row.getObjectType(), row.getObjectId(), row.getResult(), row.getSummary(),
            row.getCreatedAt());
    }
}
