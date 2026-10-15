package com.stonewu.agenteam.configuration.audit;

import com.stonewu.agenteam.service.audit.AuditChainSealing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动后把迁移前遗留的审计记录接到哈希链上；每批独立提交，重复启动不会重复封印。
 * 单次启动的批次上限用于避免历史数据过多时拖慢启动，剩余记录下次启动继续。
 */
@Component
public class AuditChainSealingInitialization implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AuditChainSealingInitialization.class);

    private final AuditChainSealing sealing;

    private final boolean enabled;

    private final int batchSize;

    private final int maxBatches;

    public AuditChainSealingInitialization(AuditChainSealing sealing,
                                           @Value("${agenteam.audit.chain.sealing-enabled:true}") boolean enabled,
                                           @Value("${agenteam.audit.chain.sealing-batch-size:500}") int batchSize,
                                           @Value("${agenteam.audit.chain.sealing-max-batches:20}") int maxBatches) {
        this.sealing = sealing;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) {
            return;
        }
        try {
            int sealed = sealing.sealPending(batchSize, maxBatches);
            if (sealed > 0) {
                LOG.info("审计哈希链已封印历史记录 {} 条", sealed);
            }
        } catch (RuntimeException failure) {
            // 封印失败不影响业务可用，下次启动会继续补齐。
            LOG.warn("审计哈希链历史记录封印未完成，将在下次启动继续", failure);
        }
    }
}
