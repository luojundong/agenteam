package com.stonewu.agenteam.model.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 审计哈希链链头：每个作用域一行，写入时先锁该行，把同作用域的追加串行化。
 */
@Getter
@Setter
@TableName("audit_event_chain")
public class AuditEventChainRow {
    @TableId(value = "scope_key", type = IdType.INPUT)
    private String scopeKey;

    @TableField(value = "last_event_id")
    private String lastEventId;

    @TableField(value = "last_hash")
    private String lastHash;

    @TableField(value = "updated_at")
    private Instant updatedAt;
}
