package com.stonewu.agenteam.service.audit;

import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * 把迁移前遗留的审计记录按发生时间顺序接到哈希链上（封印）。
 *
 * <p>迁移只加列不加数据：SQL 无法表达 hash_i 依赖 hash_i-1 的递推关系，且 JSON 列在
 * SQL 与 Java 两侧的规范化结果不一致，因此回填统一由应用用同一套算法完成。
 * 封印按批进行、每批一个独立事务，中断后下次启动可继续；未封印的记录不计入链连续性校验，
 * 但内容仍受单条哈希保护（校验会报"没有任何已封印记录"之外的缺失提示）。
 */
@Service
public class AuditChainSealing {

    private final AuditEventMapper mapper;
    private final AuditHashChain hashChain;
    private final Clock clock;
    private final TransactionTemplate detachedTransaction;

    public AuditChainSealing(AuditEventMapper mapper, AuditHashChain hashChain, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.hashChain = hashChain;
        this.clock = clock;
        this.detachedTransaction = new TransactionTemplate(transactionManager);
        this.detachedTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 封印一个作用域内最多 {@code batchSize} 条未封印记录，返回实际封印条数。
     * 使用独立事务，避免把回填与调用方的业务事务绑在一起。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sealBatch(String enterpriseId, int batchSize) {
        var rows = mapper.listUnsealed(enterpriseId, batchSize);
        for (var row : rows) {
            var stamp = clock.instant();
            row.setPrevHash(mapper.lockPreviousHash(enterpriseId, stamp));
            row.setHash(hashChain.seal(row));
            mapper.updateSeal(row);
            mapper.advanceChain(enterpriseId, row.getId(), row.getHash(), stamp);
        }
        return rows.size();
    }

    /**
     * 逐个作用域推进，直到没有未封印记录或达到 {@code maxBatches} 批上限。
     */
    public int sealPending(int batchSize, int maxBatches) {
        int sealed = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<String> scopes = mapper.pendingSealScopes(1);
            if (scopes.isEmpty()) {
                return sealed;
            }
            String enterpriseId = scopes.getFirst();
            int count = detachedTransaction.execute(status -> sealBatch(enterpriseId, batchSize));
            sealed += count;
            if (count == 0) {
                return sealed;
            }
        }
        return sealed;
    }
}
