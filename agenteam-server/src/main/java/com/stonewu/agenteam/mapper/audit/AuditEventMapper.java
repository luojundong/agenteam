package com.stonewu.agenteam.mapper.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 只追加已完成的重要操作；调用方只传递允许记录的业务差异。
 */
@Repository
public class AuditEventMapper {

    private final AuditEventSqlMapper statements;

    private final IdentityQueryMapper identityQueryMapper;

    public AuditEventMapper(AuditEventSqlMapper statements, IdentityQueryMapper identityQueryMapper) {
        this.identityQueryMapper = identityQueryMapper;
        this.statements = statements;
    }

    public void append(String enterpriseId, String userId, String name, String action, String objectType,
                       String objectId, String summary, String detailJson, String requestId, Instant now) {
        append(enterpriseId, userId, name, action, objectType, objectId, summary, detailJson, "success", requestId, now);
    }

    public void append(String enterpriseId, String userId, String name, String action, String objectType,
                       String objectId, String summary, String detailJson, String result, String requestId, Instant now) {
        String actorName = resolveActorName(enterpriseId, userId, name);
        statements.appendAuditEvent(UUID.randomUUID().toString(), enterpriseId, userId, actorName, action, objectType,
            objectId, summary, detailJson, result, requestId, Timestamp.from(now));
    }

    /**
     * 优先采用调用方传入的权威展示名（来自已认证操作者），避免每次审计写入都额外查询成员表；
     * 仅当展示名缺失时回退到成员表查询，仍缺失则保留原始值。
     */
    private String resolveActorName(String enterpriseId, String userId, String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return identityQueryMapper.appendEnterpriseMember(enterpriseId, userId).stream().findFirst().orElse(provided);
    }

    public List<AuditEventRow> query(String enterpriseId, String actorUserId, String action, String objectType,
                                    String result, Instant from, Instant to, Instant lastTime, String lastId, int size) {
        var wrapper = new QueryWrapper<AuditEventRow>().eq("enterprise_id", enterpriseId);
        if (actorUserId != null) {
            wrapper.eq("actor_user_id", actorUserId);
        }
        if (action != null) {
            wrapper.eq("action", action);
        }
        if (objectType != null) {
            wrapper.eq("object_type", objectType);
        }
        if (result != null) {
            wrapper.eq("result", result);
        }
        wrapper.ge("created_at", from).le("created_at", to);
        if (lastTime != null) {
            wrapper.and(condition -> condition.lt("created_at", lastTime)
                .or(inner -> inner.eq("created_at", lastTime).lt("id", lastId)));
        }
        wrapper.orderByDesc("created_at").orderByDesc("id").last("LIMIT " + (size + 1));
        return statements.selectList(wrapper);
    }
}
