package com.stonewu.agenteam.mapper.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventChainRow;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.service.audit.AuditHashChain;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 只追加已完成的重要操作；调用方只传递允许记录的业务差异。
 *
 * <p>每条记录都会写入 {@code prev_hash} 与 {@code hash} 串联成防篡改哈希链。链头的写入
 * 必须在事务内完成，否则 {@code SELECT ... FOR UPDATE} 的行锁会在语句结束时就释放，
 * 并发追加仍可能取到同一个链尾而分叉；因此这里用 {@code REQUIRED} 的事务模板包住关键区，
 * 调用方已有事务时直接加入，没有事务时自行开启一个。
 */
@Repository
public class AuditEventMapper {

    private final AuditEventSqlMapper statements;

    private final IdentityQueryMapper identityQueryMapper;

    private final AuditHashChain hashChain;

    private final TransactionTemplate transaction;

    public AuditEventMapper(AuditEventSqlMapper statements, IdentityQueryMapper identityQueryMapper,
                            AuditHashChain hashChain, PlatformTransactionManager transactionManager) {
        this.identityQueryMapper = identityQueryMapper;
        this.statements = statements;
        this.hashChain = hashChain;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void append(String enterpriseId, String userId, String name, String action, String objectType,
                       String objectId, String summary, String detailJson, String requestId, Instant now) {
        append(enterpriseId, userId, name, action, objectType, objectId, summary, detailJson, "success", requestId, now);
    }

    public void append(String enterpriseId, String userId, String name, String action, String objectType,
                       String objectId, String summary, String detailJson, String result, String requestId, Instant now) {
        String actorName = resolveActorName(enterpriseId, userId, name);
        transaction.executeWithoutResult(status -> appendSealed(enterpriseId, userId, actorName, action, objectType,
            objectId, summary, detailJson, result, requestId, now));
    }

    private void appendSealed(String enterpriseId, String userId, String actorName, String action, String objectType,
                              String objectId, String summary, String detailJson, String result, String requestId,
                              Instant now) {
        String scope = hashChain.scopeOf(enterpriseId);
        // DATETIME(3) 只到毫秒：先用同样的精度参与摘要，否则写入时用纳秒、读回时已被截断，
        // 校验重算必然不一致。截断后不存在亚毫秒位，MySQL 也不会再做四舍五入。
        Instant stored = (now == null ? null : now.truncatedTo(ChronoUnit.MILLIS));
        Instant stamp = (stored == null ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : stored);
        statements.ensureChain(scope, Timestamp.from(stamp));
        var head = statements.lockChain(scope);
        String previous = head == null || head.getLastHash() == null || head.getLastHash().isBlank()
            ? null
            : head.getLastHash();
        String id = UUID.randomUUID().toString();
        var row = new AuditEventRow();
        row.setId(id);
        row.setEnterpriseId(enterpriseId);
        row.setActorUserId(userId);
        row.setActorName(actorName);
        row.setAction(action);
        row.setObjectType(objectType);
        row.setObjectId(objectId);
        row.setResult(result);
        row.setSummary(summary);
        row.setDetailRedactedJson(detailJson);
        row.setRequestId(requestId);
        row.setCreatedAt(stored);
        row.setPrevHash(previous);
        row.setHash(hashChain.seal(row));
        statements.appendAuditEvent(id, enterpriseId, userId, actorName, action, objectType, objectId, summary,
            detailJson, result, requestId, row.getPrevHash(), row.getHash(),
            stored == null ? null : Timestamp.from(stored));
        var chain = new AuditEventChainRow();
        chain.setScopeKey(scope);
        chain.setLastEventId(id);
        chain.setLastHash(row.getHash());
        chain.setUpdatedAt(stamp);
        statements.advanceChain(chain);
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

    /**
     * 读取链头（不加锁），用于校验与观察。
     */
    public AuditEventChainRow chainOf(String enterpriseId) {
        return statements.findChain(hashChain.scopeOf(enterpriseId));
    }

    /**
     * 占住作用域链头并返回链尾哈希；必须在事务内调用，否则行锁会立即释放。
     */
    public String lockPreviousHash(String enterpriseId, Instant stamp) {
        String scope = hashChain.scopeOf(enterpriseId);
        statements.ensureChain(scope, Timestamp.from(stamp));
        var head = statements.lockChain(scope);
        return head == null || head.getLastHash() == null || head.getLastHash().isBlank()
            ? null
            : head.getLastHash();
    }

    /**
     * 把链头推进到指定记录；必须在事务内调用，且与 {@link #lockPreviousHash} 在同一事务中。
     */
    public void advanceChain(String enterpriseId, String eventId, String hash, Instant stamp) {
        var chain = new AuditEventChainRow();
        chain.setScopeKey(hashChain.scopeOf(enterpriseId));
        chain.setLastEventId(eventId);
        chain.setLastHash(hash);
        chain.setUpdatedAt(stamp);
        statements.advanceChain(chain);
    }

    /**
     * 按发生时间升序列出整个作用域的记录，供哈希链校验使用。
     */
    public List<AuditEventRow> listByScope(String enterpriseId) {
        return statements.selectList(scopeWrapper(enterpriseId).orderByAsc("created_at", "id"));
    }

    /**
     * 列出尚未封印（{@code hash} 为空）的记录，按发生时间升序，供回填按序接链。
     */
    public List<AuditEventRow> listUnsealed(String enterpriseId, int limit) {
        return statements.selectList(scopeWrapper(enterpriseId).isNull("hash")
            .orderByAsc("created_at", "id").last("LIMIT " + limit));
    }

    /**
     * 存在未封印记录的作用域；结果里的 {@code null} 代表全局身份动作作用域。
     */
    public List<String> pendingSealScopes(int limit) {
        return statements.pendingSealScopes(limit);
    }

    /**
     * 回填/重算后更新记录的链字段。
     */
    public void updateSeal(AuditEventRow row) {
        statements.updateById(row);
    }

    private QueryWrapper<AuditEventRow> scopeWrapper(String enterpriseId) {
        var wrapper = new QueryWrapper<AuditEventRow>();
        return enterpriseId == null ? wrapper.isNull("enterprise_id") : wrapper.eq("enterprise_id", enterpriseId);
    }
}
