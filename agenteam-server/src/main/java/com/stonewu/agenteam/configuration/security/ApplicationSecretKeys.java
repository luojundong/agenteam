package com.stonewu.agenteam.configuration.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 显式配置优先，未配置的密钥从本机文件生成并复用；请求摘要与内容加密使用独立密钥。
 *
 * <p>本机文件是<strong>仅供开发</strong>的回落：它与运行数据同机保存、没有备份，
 * 重新部署即丢失，届时已加密内容与历史分页游标都无法再读取。因此生产环境
 * 缺少任一密钥时直接拒绝启动，而不是悄悄生成一个——多实例部署下各实例还会各自
 * 生成不同密钥，签名互不认账，问题会以更难排查的形式延后暴露。
 */
@Component
public class ApplicationSecretKeys {

    private final SecretKey requestKey;

    private final Map<String, SecretKey> encryptionKeys;

    private final String activeVersion;

    /**
     * 是否有任一密钥取自本机文件，而不是由部署注入。
     */
    private final boolean generatedLocally;

    /**
     * 非 {@code production} 环境的入口：允许回落到本机文件。测试与容器缺省路径走这里。
     */
    public ApplicationSecretKeys(@Value("${agenteam.security.request-signing-key:}") String signingKey,
                                 @Value("${agenteam.security.encryption-keys:}") String configuredKeys,
                                 @Value("${agenteam.security.active-encryption-key:1}") String activeVersion,
                                 @Value("${agenteam.security.keys-file:.agenteam/security/application-keys.properties}") String keysFile) {
        this(false, signingKey, configuredKeys, activeVersion, keysFile);
    }

    /**
     * 容器实际选用的入口：生产 profile 下不允许回落到本机文件。
     *
     * <p>判定放在这里而不是某个后置的启动检查里，是因为密钥一旦生成就会先落盘，
     * 后置检查只能在文件已经写出之后报错。这里必须<strong>在生成之前</strong>拦住。
     */
    @Autowired
    public ApplicationSecretKeys(Environment environment,
                                 @Value("${agenteam.security.request-signing-key:}") String signingKey,
                                 @Value("${agenteam.security.encryption-keys:}") String configuredKeys,
                                 @Value("${agenteam.security.active-encryption-key:1}") String activeVersion,
                                 @Value("${agenteam.security.keys-file:.agenteam/security/application-keys.properties}") String keysFile) {
        this(environment.matchesProfiles("production"), signingKey, configuredKeys, activeVersion, keysFile);
    }

    private ApplicationSecretKeys(boolean production, String signingKey, String configuredKeys, String activeVersion,
                                  String keysFile) {
        if (!activeVersion.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("内容加密密钥版本格式不正确");
        }
        SecretKey signing = signingKey.isBlank() ? null : decode(signingKey, "HmacSHA256");
        Map<String, SecretKey> keys = decodeEncryptionKeys(configuredKeys, activeVersion);
        boolean fromFile = false;
        if (signing == null || keys.isEmpty()) {
            if (production) {
                throw new IllegalArgumentException("生产环境不允许自动生成部署密钥：" + missingKeys(signing == null, keys.isEmpty())
                    + "，必须由部署环境注入后重启。");
            }
            fromFile = true;
            var generated = ApplicationSecretKeyFile.loadOrCreate(keysFile, activeVersion);
            if (signing == null) {
                signing = decode(generated.signingKey(), "HmacSHA256");
            }
            if (keys.isEmpty()) {
                keys = decodeEncryptionKeys(generated.encryptionKeys(), activeVersion);
            }
        }
        requestKey = signing;
        encryptionKeys = Map.copyOf(keys);
        this.activeVersion = activeVersion;
        this.generatedLocally = fromFile;
    }

    private static String missingKeys(boolean withoutSigning, boolean withoutEncryption) {
        var names = new ArrayList<String>();
        if (withoutSigning) {
            names.add("AGENTEAM_REQUEST_SIGNING_KEY");
        }
        if (withoutEncryption) {
            names.add("AGENTEAM_ENCRYPTION_KEYS");
        }
        return String.join(" 与 ", names);
    }

    /**
     * 是否有任一密钥来自本机文件。这类密钥与数据同机保存、无备份、不可跨实例共用，
     * 只适用于本地开发；生产必须外部注入。
     */
    public boolean generatedLocally() {
        return generatedLocally;
    }

    private Map<String, SecretKey> decodeEncryptionKeys(String configuredKeys, String activeVersion) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        if (!configuredKeys.isBlank()) {
            try {
                Map<String, String> encoded = new ObjectMapper().readValue(configuredKeys, new TypeReference<>() {
                });
                if (encoded.isEmpty() || encoded.size() > 20) {
                    throw new IllegalArgumentException();
                }
                for (var entry : encoded.entrySet()) {
                    if (!entry.getKey().matches("[A-Za-z0-9_-]{1,64}")) {
                        throw new IllegalArgumentException();
                    }
                    keys.put(entry.getKey(), decode(entry.getValue(), "AES"));
                }
                if (!keys.containsKey(activeVersion)) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception exception) {
                throw new IllegalArgumentException("内容加密密钥配置格式不正确");
            }
        }
        return keys;
    }

    public SecretKey requestKey() {
        return requestKey;
    }

    public String activeVersion() {
        encryptionKey(activeVersion);
        return activeVersion;
    }

    public SecretKey encryptionKey(String version) {
        SecretKey key = encryptionKeys.get(version);
        if (key == null) {
            throw unavailable(
                "未找到版本 " + version + " 的内容加密密钥，请检查 agenteam.security.encryption-keys 和 agenteam.security.active-encryption-key。");
        }
        return key;
    }

    private SecretKey decode(String value, String algorithm) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(bytes, algorithm);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("部署密钥必须是经过 Base64 编码的三十二字节随机值");
        }
    }

    private ApiException unavailable(String reason) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "暂时无法提交此操作，请联系管理员。", new IllegalStateException(reason));
    }
}
