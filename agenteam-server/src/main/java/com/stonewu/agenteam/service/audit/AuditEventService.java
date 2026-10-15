package com.stonewu.agenteam.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 将明确列出的业务差异写入审计表。不接受整份请求、密码或用户实体作为详情。
 *
 * <p><b>事务语义（不能随意统一）</b>：
 * <ul>
 *   <li><b>成功</b>审计加入调用方事务（REQUIRED）：业务回滚时成功审计一并回滚，
 *       避免出现"操作实际没发生、审计却留下成功记录"的不一致。</li>
 *   <li><b>失败</b>审计使用独立事务（REQUIRES_NEW）：业务异常回滚时失败审计仍落库，
 *       否则失败尝试会被吞掉，失去审计意义。</li>
 * </ul>
 * 这里用 TransactionTemplate 而不是方法级注解，是因为同一个方法无法按参数切换传播行为，
 * 且同类自调用会绕过代理导致注解失效。
 */
@Service
public class AuditEventService {
    public static final String REQUEST_ID_ATTRIBUTE = "agenteam.request-id";
    private final AuditEventMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate joinedTransaction;
    private final TransactionTemplate detachedTransaction;

    public AuditEventService(AuditEventMapper mapper, ObjectMapper json, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
        this.joinedTransaction = new TransactionTemplate(transactionManager);
        this.detachedTransaction = new TransactionTemplate(transactionManager);
        this.detachedTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String enterpriseId, UserEntity actor, String action, String objectType,
                       String objectId, String summary, Map<String, ?> detail) {
        record(enterpriseId, actor, action, objectType, objectId, summary, detail, true);
    }

    /**
     * 记录带明确结果的审计；失败路径传入 {@code false}，使 {@code audit_event.result} 反映真实成败。
     */
    public void record(String enterpriseId, UserEntity actor, String action, String objectType,
                       String objectId, String summary, Map<String, ?> detail, boolean success) {
        var template = success ? joinedTransaction : detachedTransaction;
        template.executeWithoutResult(status ->
            record(enterpriseId, actor, action, objectType, objectId, summary, detail, success ? "success" : "failure"));
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
