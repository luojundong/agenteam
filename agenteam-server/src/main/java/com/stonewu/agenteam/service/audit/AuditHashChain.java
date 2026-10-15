package com.stonewu.agenteam.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 审计记录的防篡改哈希：把一条记录的关键字段规范化成定长摘要，并与上一条记录的哈希串联。
 *
 * <p>规范化要点：
 * <ul>
 *   <li>可空字段统一用 {@code -} 占位，字段再带长度前缀，null、空串与"正好等于占位符的内容"
 *       三者不会产生相同摘要；</li>
 *   <li>分隔符 {@code |} 与转义符 {@code \} 先转义，保证字段拼接可逆、不会被构造出歧义；</li>
 *   <li>{@code created_at} 用固定精度（毫秒、UTC）格式化，避免时区与精度差异造成摘要漂移。</li>
 * </ul>
 */
@Component
public final class AuditHashChain {
    /** 全局身份动作（enterprise_id 为空）所属的作用域编号。 */
    public static final String GLOBAL_SCOPE = "-";
    /** 空值占位符。 */
    public static final String EMPTY_MARKER = "-";

    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;

    public AuditHashChain(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 作用域编号：按企业分链；全局动作用 {@link #GLOBAL_SCOPE}。
     */
    public String scopeOf(String enterpriseId) {
        return enterpriseId == null || enterpriseId.isBlank() ? GLOBAL_SCOPE : enterpriseId;
    }

    /**
     * 按记录当前内容重算哈希，可与库里的 {@code hash} 比对判断内容是否被篡改。
     */
    public String seal(AuditEventRow row) {
        return sha256(canonical(row));
    }

    public boolean intact(AuditEventRow row) {
        return row.getHash() != null && row.getHash().equals(seal(row));
    }

    private String canonical(AuditEventRow row) {
        return String.join("|",
            field(row.getPrevHash()),
            field(row.getId()),
            field(row.getEnterpriseId()),
            field(row.getActorUserId()),
            field(row.getActorName()),
            field(row.getAction()),
            field(row.getObjectType()),
            field(row.getObjectId()),
            field(row.getResult()),
            field(row.getSummary()),
            // JSON 列由 MySQL 重新序列化（键间会补空格、数字可能改写），
            // 写入时与校验时都先解析再规范化输出，避免两侧文本不同造成摘要漂移。
            field(normalizedJson(row.getDetailRedactedJson())),
            field(row.getRequestId()),
            row.getCreatedAt() == null ? EMPTY_MARKER : CREATED_AT_FORMAT.format(row.getCreatedAt()));
    }

    private String normalizedJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value).toString();
        } catch (Exception exception) {
            return value;
        }
    }

    /**
     * 字段写成「长度:内容」：长度前缀保证拼接可逆，null 与空串、与正好等于占位符的内容
     * 都不会产生相同摘要；分隔符与转义符再转义一次，避免构造出歧义。
     */
    private String field(String value) {
        if (value == null) {
            return EMPTY_MARKER;
        }
        return value.length() + ":" + value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private String sha256(String canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            var text = new StringBuilder(hash.length * 2);
            for (byte piece : hash) {
                text.append(Character.forDigit((piece >> 4) & 0xF, 16)).append(Character.forDigit(piece & 0xF, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256，无法写入防篡改审计", exception);
        }
    }
}
