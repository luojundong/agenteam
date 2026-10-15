package com.stonewu.agenteam.service.audit;

import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 MySQL 上验证审计哈希链：连续写入串联、内容篡改、删除中间记录、
 * 改前驱哈希、并发写入不分叉、以及迁移前遗留记录的封印回填。
 * 审计表没有企业外键，测试用独立的作用域编号即可，不依赖企业开通流程。
 */
@SpringBootTest(properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false"})
@Import(SharedEnterpriseTestEdition.class)
class AuditHashChainIntegrationTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private AuditEventMapper audit;

    @Autowired
    private AuditEventSqlMapper statements;

    @Autowired
    private AuditChainVerifier verifier;

    @Autowired
    private AuditChainSealing sealing;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    static void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void consecutiveAppendsFormOneVerifiableChain() {
        String scope = scope("chain");
        var start = Instant.parse("2026-10-15T12:00:00Z");
        for (int index = 0; index < 5; index++) {
            append(scope, "obj-" + index, start.plusSeconds(index));
        }
        var result = verifier.verify(scope);
        assertTrue(result.intact(), result.problem());
        assertEquals(5, result.sealed());

        var rows = audit.listByScope(scope);
        assertEquals(5, rows.size());
        assertNull(rows.getFirst().getPrevHash(), "链首不应有前驱哈希");
        for (int index = 1; index < rows.size(); index++) {
            assertEquals(rows.get(index - 1).getHash(), rows.get(index).getPrevHash(), "前驱哈希必须严格串联");
        }
        var head = audit.chainOf(scope);
        assertNotNull(head);
        assertEquals(rows.getLast().getHash(), head.getLastHash());
        assertEquals(rows.getLast().getId(), head.getLastEventId());
    }

    @Test
    void tamperedContentIsDetected() {
        String scope = scope("tamper");
        var start = Instant.parse("2026-10-15T12:00:00Z");
        for (int index = 0; index < 3; index++) {
            append(scope, "obj-" + index, start.plusSeconds(index));
        }
        var middle = audit.listByScope(scope).get(1);
        middle.setSummary("被篡改的摘要");
        audit.updateSeal(middle);

        var result = verifier.verify(scope);
        assertFalse(result.intact());
        assertEquals(middle.getId(), result.problemEventId());
        assertTrue(result.problem().contains("内容"));
    }

    @Test
    void deletedRowBreaksTheChain() {
        String scope = scope("delete");
        var start = Instant.parse("2026-10-15T12:00:00Z");
        for (int index = 0; index < 4; index++) {
            append(scope, "obj-" + index, start.plusSeconds(index));
        }
        var removed = audit.listByScope(scope).get(2);
        statements.deleteById(removed.getId());

        var result = verifier.verify(scope);
        assertFalse(result.intact());
        assertTrue(result.problem().contains("缺失"), result.problem());
    }

    @Test
    void rewrittenPreviousHashIsDetected() {
        String scope = scope("previous");
        var start = Instant.parse("2026-10-15T12:00:00Z");
        for (int index = 0; index < 3; index++) {
            append(scope, "obj-" + index, start.plusSeconds(index));
        }
        var last = audit.listByScope(scope).getLast();
        last.setPrevHash("f".repeat(64));
        audit.updateSeal(last);

        var result = verifier.verify(scope);
        assertFalse(result.intact());
    }

    @Test
    void concurrentAppendsDoNotForkTheChain() throws Exception {
        String scope = scope("concurrent");
        int threads = 2;
        int perThread = 10;
        var ready = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var workers = new Thread[threads];
        for (int index = 0; index < threads; index++) {
            int worker = index;
            workers[index] = new Thread(() -> {
                try {
                    ready.await();
                    for (int step = 0; step < perThread; step++) {
                        audit.append(scope, "user-" + worker, "审计测试员", "audit.test", "audit_event",
                            "obj-" + worker + "-" + step, "并发摘要", "{}", UUID.randomUUID().toString(), Instant.now());
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "audit-chain-" + index);
            workers[index].start();
        }
        ready.countDown();
        for (var worker : workers) {
            worker.join();
        }
        assertTrue(failures.isEmpty(), () -> "并发追加出现异常：" + failures);

        var result = verifier.verify(scope);
        assertTrue(result.intact(), result.problem());
        assertEquals(threads * perThread, result.sealed());
    }

    @Test
    void recordsWrittenBeforeTheMigrationAreSealedOntoTheChain() {
        String scope = scope("legacy");
        var start = Instant.parse("2026-10-15T11:00:00Z");
        for (int index = 0; index < 3; index++) {
            var row = new AuditEventRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(scope);
            row.setActorUserId("user-1");
            row.setActorName("审计测试员");
            row.setAction("audit.test");
            row.setObjectType("audit_event");
            row.setObjectId("legacy-" + index);
            row.setResult("success");
            row.setSummary("迁移前的记录");
            row.setDetailRedactedJson("{}");
            row.setRequestId(UUID.randomUUID().toString());
            row.setCreatedAt(start.plusSeconds(index));
            statements.insert(row);
        }
        assertTrue(verifier.verify(scope).problem().contains("已封印"), "未封印的记录不应通过校验");

        assertEquals(3, sealing.sealPending(10, 5));
        var result = verifier.verify(scope);
        assertTrue(result.intact(), result.problem());
        assertEquals(3, result.sealed());
    }

    private void append(String scope, String objectId, Instant now) {
        audit.append(scope, "user-1", "审计测试员", "audit.test", "audit_event", objectId, "摘要", "{}",
            UUID.randomUUID().toString(), now);
    }

    private String scope(String name) {
        return "hash-chain-" + name + "-" + UUID.randomUUID();
    }
}
