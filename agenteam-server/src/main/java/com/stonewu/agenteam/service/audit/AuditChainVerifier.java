package com.stonewu.agenteam.service.audit;

import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按作用域重算审计哈希链，发现内容被改、记录被删或链被分叉。
 *
 * <p>校验沿 prev_hash → hash 的链接走查，而不是按业务时间排序走查：
 * 链序等于写入顺序（历史回填的记录接在链尾），业务时间只用于显示与筛选，
 * 用链接走查可以避免因时间回拨或并发写入造成误报。
 */
@Service
public class AuditChainVerifier {

    /**
     * @param scope            作用域编号（企业号，或全局动作用的 {@link AuditHashChain#GLOBAL_SCOPE}）
     * @param total            作用域内记录总数
     * @param sealed           已封印记录数
     * @param intact           链是否完整（无内容篡改、无断链、无分叉、链尾与链头一致）
     * @param problemEventId   首个出现问题的记录编号，无问题时为空
     * @param problem          问题描述，无问题时为空
     */
    public record Verification(String scope, int total, int sealed, boolean intact,
                               String problemEventId, String problem) {
        public static Verification healthy(String scope, int total, int sealed) {
            return new Verification(scope, total, sealed, true, null, null);
        }

        public static Verification broken(String scope, int total, int sealed, String problemEventId, String problem) {
            return new Verification(scope, total, sealed, false, problemEventId, problem);
        }
    }

    private final AuditEventMapper mapper;
    private final AuditHashChain hashChain;

    public AuditChainVerifier(AuditEventMapper mapper, AuditHashChain hashChain) {
        this.mapper = mapper;
        this.hashChain = hashChain;
    }

    public Verification verify(String enterpriseId) {
        String scope = hashChain.scopeOf(enterpriseId);
        List<AuditEventRow> rows = mapper.listByScope(enterpriseId);
        List<AuditEventRow> sealed = rows.stream().filter(row -> row.getHash() != null && !row.getHash().isBlank()).toList();
        if (sealed.isEmpty()) {
            return rows.isEmpty()
                ? Verification.healthy(scope, 0, 0)
                : Verification.broken(scope, rows.size(), 0, rows.getFirst().getId(), "作用域内没有任何已封印记录");
        }
        List<AuditEventRow> roots = sealed.stream()
            .filter(row -> row.getPrevHash() == null || row.getPrevHash().isBlank())
            .toList();
        if (roots.size() != 1) {
            return Verification.broken(scope, rows.size(), sealed.size(),
                roots.isEmpty() ? sealed.getFirst().getId() : roots.get(1).getId(),
                "链首记录数量为 " + roots.size() + "，哈希链应当只有一个链首");
        }
        Map<String, List<AuditEventRow>> successors = new HashMap<>();
        for (var row : sealed) {
            successors.computeIfAbsent(row.getPrevHash(), key -> new ArrayList<>()).add(row);
        }
        Set<String> visited = new HashSet<>();
        var current = roots.getFirst();
        while (current != null) {
            if (!hashChain.intact(current)) {
                return Verification.broken(scope, rows.size(), sealed.size(), current.getId(), "记录内容与哈希不符");
            }
            if (!visited.add(current.getId())) {
                return Verification.broken(scope, rows.size(), sealed.size(), current.getId(), "哈希链出现回环");
            }
            List<AuditEventRow> next = successors.get(current.getHash());
            if (next == null || next.isEmpty()) {
                break;
            }
            if (next.size() > 1) {
                return Verification.broken(scope, rows.size(), sealed.size(), next.get(1).getId(),
                    "多条记录指向同一个前驱，哈希链出现分叉");
            }
            current = next.getFirst();
        }
        if (visited.size() != sealed.size()) {
            var orphan = sealed.stream().filter(row -> !visited.contains(row.getId())).findFirst().orElseThrow();
            return Verification.broken(scope, rows.size(), sealed.size(), orphan.getId(),
                "存在无法从链首到达的记录，链中间可能缺失记录");
        }
        var head = mapper.chainOf(enterpriseId);
        if (head == null || !current.getHash().equals(head.getLastHash())) {
            return Verification.broken(scope, rows.size(), sealed.size(), current.getId(), "链尾与链头记录不一致");
        }
        return Verification.healthy(scope, rows.size(), sealed.size());
    }
}
