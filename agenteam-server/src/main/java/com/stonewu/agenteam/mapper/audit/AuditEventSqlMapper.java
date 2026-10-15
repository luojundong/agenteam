package com.stonewu.agenteam.mapper.audit;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventChainRow;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.sql.Timestamp;
import java.util.List;

/**
 * AuditEventMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface AuditEventSqlMapper extends MPJBaseMapper<AuditEventRow> {

    default int appendAuditEvent(String id, String enterpriseId, String userId, String actorName, String action,
                                 String objectType, String objectId, String summary, String detailJson,
                                 String result, String requestId, String prevHash, String hash, Timestamp now) {
        var databaseRow = new AuditEventRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setActorUserId(userId);
        databaseRow.setActorName(actorName);
        databaseRow.setAction(action);
        databaseRow.setObjectType(objectType);
        databaseRow.setObjectId(objectId);
        databaseRow.setResult(result);
        databaseRow.setSummary(summary);
        databaseRow.setDetailRedactedJson(detailJson);
        databaseRow.setRequestId(requestId);
        databaseRow.setPrevHash(prevHash);
        databaseRow.setHash(hash);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    @Select("SELECT scope_key, last_event_id, last_hash, updated_at FROM audit_event_chain"
        + " WHERE scope_key = #{scope}")
    AuditEventChainRow findChain(@Param("scope") String scope);

    /**
     * 先占住链头行，保证同一作用域的追加串行化：并发写入会在此阻塞，
     * 直到前一个事务提交后再读取链尾，避免两条记录取到同一个 prev_hash 造成分叉。
     */
    @Select("SELECT scope_key, last_event_id, last_hash, updated_at FROM audit_event_chain"
        + " WHERE scope_key = #{scope} FOR UPDATE")
    AuditEventChainRow lockChain(@Param("scope") String scope);

    /**
     * 链头行不存在时先占位（空哈希），否则 {@code lockChain} 只能锁到间隙锁，
     * 两个事务会各自把首条记录当作链首而分叉。重复执行时是空更新，不改变已有链尾。
     */
    @Insert("INSERT INTO audit_event_chain (scope_key, last_event_id, last_hash, updated_at)"
        + " VALUES (#{scope}, '', '', #{updatedAt})"
        + " ON DUPLICATE KEY UPDATE updated_at = updated_at")
    int ensureChain(@Param("scope") String scope, @Param("updatedAt") Timestamp updatedAt);

    @Insert("INSERT INTO audit_event_chain (scope_key, last_event_id, last_hash, updated_at)"
        + " VALUES (#{row.scopeKey}, #{row.lastEventId}, #{row.lastHash}, #{row.updatedAt})"
        + " ON DUPLICATE KEY UPDATE last_event_id = VALUES(last_event_id),"
        + " last_hash = VALUES(last_hash), updated_at = VALUES(updated_at)")
    int advanceChain(@Param("row") AuditEventChainRow row);

    @Select("SELECT DISTINCT enterprise_id FROM audit_event WHERE hash IS NULL LIMIT #{limit}")
    List<String> pendingSealScopes(@Param("limit") int limit);
}
