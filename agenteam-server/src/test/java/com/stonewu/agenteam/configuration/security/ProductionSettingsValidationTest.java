package com.stonewu.agenteam.configuration.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSettingsValidationTest {
    @TempDir
    Path directory;

    @Test
    void requiresEncryptedMailLinksAndRestrictedProxyAddresses() {
        var keys = keys();
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment(), keys));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("agenteam.web.public-base-url", "http://agenteam.example.test"), keys));
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment()
            .withProperty("agenteam.web.public-base-url", "https://new-address.example.test:9443"), keys));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("server.tomcat.remoteip.internal-proxies", ".*"), keys));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("server.tomcat.remoteip.internal-proxies", ".+"), keys));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("server.tomcat.remoteip.internal-proxies", "10.0.0.0/8"), keys));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("server.servlet.session.cookie.secure", "false"), keys));
    }

    @Test
    void acceptsGeneratedKeysAndRejectsInvalidServiceSettings() {
        var generated = new ApplicationSecretKeys("", "", "1", directory.resolve("keys.properties").toString());
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment(), generated));
        // 清单化的目的是一次报出全部问题，因此配置错误统一走启动期异常，
        // 不再让某一项单独抛出「服务不可用」。
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettingsValidation(environment()
            .withProperty("agenteam.mail.from", "not-an-address"), keys()));
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment()
            .withProperty("files.storage", "local"), keys()));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        var broken = environment().withProperty("agenteam.web.public-base-url", "http://agenteam.example.test")
            .withProperty("server.servlet.session.cookie.secure", "false")
            .withProperty("server.tomcat.remoteip.internal-proxies", "10.0.0.0/8")
            .withProperty("agenteam.mail.from", "not-an-address");
        var message = assertThrows(IllegalArgumentException.class,
            () -> new ProductionSettingsValidation(broken, keys())).getMessage();
        assertTrue(message.contains("共 4 项"), message);
        assertTrue(message.contains(ProductionSettingsValidation.Check.PUBLIC_ORIGIN.name()), message);
        assertTrue(message.contains(ProductionSettingsValidation.Check.SESSION_COOKIE.name()), message);
        assertTrue(message.contains(ProductionSettingsValidation.Check.TRUSTED_PROXIES.name()), message);
        assertTrue(message.contains(ProductionSettingsValidation.Check.MAIL_FROM.name()), message);
    }

    @Test
    void marksKeysThatCameFromTheLocalMachine() {
        var generated = new ApplicationSecretKeys("", "", "1", directory.resolve("generated.properties").toString());
        assertTrue(generated.generatedLocally(), "未注入密钥时应标记为本机生成");
        assertFalse(keys().generatedLocally(), "注入密钥后不应再使用本机文件");
        assertDoesNotThrow(() -> new LocalSecretKeyNotice(generated));
        assertDoesNotThrow(() -> new LocalSecretKeyNotice(keys()));
    }

    @Test
    void acceptsMissingBlankOrConfiguredVirusScanner() {
        var keys = keys();
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment(), keys));
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment().withProperty("files.scan.host", ""), keys));
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment().withProperty("files.scan.host", " \t"), keys));
        assertDoesNotThrow(() -> new ProductionSettingsValidation(environment().withProperty("files.scan.host", "scanner.example.test"), keys));
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("agenteam.web.public-base-url", "https://agenteam.example.test")
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("server.tomcat.remoteip.internal-proxies", "127.0.0.1/32")
            .withProperty("spring.mail.host", "mail.example.test")
            .withProperty("files.storage", "s3")
            .withProperty("agenteam.mail.from", "agenteam@example.test");
    }

    private ApplicationSecretKeys keys() {
        byte[] signing = new byte[32];
        byte[] encryption = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(signing);
        random.nextBytes(encryption);
        return new ApplicationSecretKeys(Base64.getEncoder().encodeToString(signing),
            "{\"1\":\"" + Base64.getEncoder().encodeToString(encryption) + "\"}", "1", directory.resolve("unused.properties").toString());
    }
}
