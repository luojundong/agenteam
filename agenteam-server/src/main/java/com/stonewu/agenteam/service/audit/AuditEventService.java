package com.stonewu.agenteam.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 将明确列出的业务差异写入当前事务，不接受整份请求、密码或用户实体作为详情。
 */
@Service
public class AuditEventService {
    public static final String REQUEST_ID_ATTRIBUTE = "agenteam.request-id";
    private final AuditEventMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditEventService(AuditEventMapper mapper, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
    }

    public void record(String enterpriseId, UserEntity actor, String action, String objectType,
                       String objectId, String summary, Map<String, ?> detail) {
        record(enterpriseId, actor, action, objectType, objectId, summary, detail, "success");
    }

    /**
     * 记录带明确结果的审计；失败路径传入 {@code false}，使 {@code audit_event.result} 反映真实成败。
     */
    public void record(String enterpriseId, UserEntity actor, String action, String objectType,
                       String objectId, String summary, Map<String, ?> detail, boolean success) {
        record(enterpriseId, actor, action, objectType, objectId, summary, detail, success ? "success" : "failure");
    }

    private void record(String enterpriseId, UserEntity actor, String action, String objectType,
                        String objectId, String summary, Map<String, ?> detail, String result) {
        try {
            mapper.append(enterpriseId, actor.id(), actor.displayName(), action, objectType, objectId, summary,
                json.writeValueAsString(detail), result, requestId(), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存操作审计，当前变更未提交", exception);
        }
    }

    private String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object current = attributes.getRequest().getAttribute(REQUEST_ID_ATTRIBUTE);
            if (current instanceof String id) {
                return id;
            }
            String id = UUID.randomUUID().toString();
            attributes.getRequest().setAttribute(REQUEST_ID_ATTRIBUTE, id);
            return id;
        }
        return UUID.randomUUID().toString();
    }
}
