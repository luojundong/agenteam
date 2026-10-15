package com.stonewu.agenteam.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哈希规范化规则的纯单元测试，不依赖数据库与容器。
 */
class AuditHashChainTest {

    private final AuditHashChain chain = new AuditHashChain(new ObjectMapper());

    @Test
    void sealProducesStableSixtyFourCharacterHexDigest() {
        var row = sample();
        assertEquals(64, chain.seal(row).length());
        assertTrue(chain.seal(row).matches("[0-9a-f]{64}"));
        assertEquals(chain.seal(row), chain.seal(clone(row)));
        assertTrue(chain.intact(seal(row)));
    }

    @Test
    void previousHashParticipatesInTheDigest() {
        var first = seal(sample());
        var second = sample();
        second.setPrevHash(first.getHash());
        assertNotEquals(first.getHash(), chain.seal(second));
        assertTrue(chain.intact(seal(second)));
    }

    @Test
    void nullAndBlankFieldsDoNotCollapseToTheSameDigest() {
        var nullValue = sample();
        nullValue.setObjectId(null);
        var blankValue = sample();
        blankValue.setObjectId("");
        assertNotEquals(chain.seal(nullValue), chain.seal(blankValue));

        var withMarkerText = sample();
        withMarkerText.setObjectId(AuditHashChain.EMPTY_MARKER);
        assertNotEquals(chain.seal(nullValue), chain.seal(withMarkerText));
    }

    @Test
    void delimiterInjectionCannotProduceTheSameDigest() {
        var escaped = sample();
        escaped.setAction("audit.test|create");
        escaped.setSummary("摘要");
        var forged = sample();
        forged.setAction("audit.test");
        forged.setSummary("create|摘要");
        assertNotEquals(chain.seal(escaped), chain.seal(forged));
    }

    @Test
    void jsonFormattingDoesNotChangeTheDigest() {
        var compact = sample();
        compact.setDetailRedactedJson("{\"userActivity\":true}");
        var spaced = sample();
        spaced.setDetailRedactedJson("{\"userActivity\": true}");
        assertEquals(chain.seal(compact), chain.seal(spaced));

        var different = sample();
        different.setDetailRedactedJson("{\"userActivity\":false}");
        assertNotEquals(chain.seal(compact), chain.seal(different));
    }

    @Test
    void everySealedFieldChangesTheDigest() {
        var baseline = chain.seal(sample());
        assertNotEquals(baseline, chain.seal(changed(row -> row.setId("event-2"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setEnterpriseId("ent-2"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setActorUserId("user-2"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setActorName("另一个审计员"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setAction("audit.export"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setObjectType("schedule"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setObjectId("obj-2"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setResult("failure"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setSummary("另一条摘要"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setRequestId("req-2"))));
        assertNotEquals(baseline, chain.seal(changed(row -> row.setCreatedAt(Instant.parse("2026-10-15T12:00:00.124Z")))));
    }

    @Test
    void tamperedHashIsDetected() {
        var row = seal(sample());
        row.setSummary("被改过的摘要");
        assertFalse(chain.intact(row));
    }

    @Test
    void enterpriseScopeFallsBackToTheGlobalMarker() {
        assertEquals("ent-1", chain.scopeOf("ent-1"));
        assertEquals(AuditHashChain.GLOBAL_SCOPE, chain.scopeOf(null));
        assertEquals(AuditHashChain.GLOBAL_SCOPE, chain.scopeOf("  "));
    }

    private AuditEventRow sample() {
        var row = new AuditEventRow();
        row.setId("event-1");
        row.setEnterpriseId("ent-1");
        row.setActorUserId("user-1");
        row.setActorName("审计测试员");
        row.setAction("audit.test");
        row.setObjectType("audit_event");
        row.setObjectId("obj-1");
        row.setResult("success");
        row.setSummary("摘要");
        row.setDetailRedactedJson("{\"userActivity\":true}");
        row.setRequestId("req-1");
        row.setCreatedAt(Instant.parse("2026-10-15T12:00:00.123Z"));
        return row;
    }

    private AuditEventRow changed(java.util.function.Consumer<AuditEventRow> change) {
        var row = sample();
        change.accept(row);
        return row;
    }

    private AuditEventRow clone(AuditEventRow source) {
        var row = new AuditEventRow();
        row.setId(source.getId());
        row.setEnterpriseId(source.getEnterpriseId());
        row.setActorUserId(source.getActorUserId());
        row.setActorName(source.getActorName());
        row.setAction(source.getAction());
        row.setObjectType(source.getObjectType());
        row.setObjectId(source.getObjectId());
        row.setResult(source.getResult());
        row.setSummary(source.getSummary());
        row.setDetailRedactedJson(source.getDetailRedactedJson());
        row.setRequestId(source.getRequestId());
        row.setCreatedAt(source.getCreatedAt());
        row.setPrevHash(source.getPrevHash());
        return row;
    }

    private AuditEventRow seal(AuditEventRow row) {
        row.setHash(chain.seal(row));
        return row;
    }
}
