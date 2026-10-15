package com.stonewu.agenteam.configuration.security;

import com.stonewu.agenteam.service.auth.AccountInputValidation;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 生产邮件链接和会话使用加密连接，部署凭据缺失时拒绝提供不能完成写入的服务。
 *
 * <p>每一项检查都登记在 {@link Check} 里，启动时逐条执行并<strong>一次报出全部问题</strong>：
 * 分到多轮才暴露的配置缺失会让部署来回返工，只报第一条则看不出还剩多少。
 * 清单内容同步维护在 {@code docs/deployment/production-checklist.md}。
 */
@Component
@Profile("production")
public class ProductionSettingsValidation {

    /**
     * 各项检查的编号与要求，与部署清单文档一一对应。
     */
    public enum Check {
        PUBLIC_ORIGIN("公开访问地址必须是 HTTPS，且不带路径、查询参数或片段"),
        SESSION_COOKIE("登录 Cookie 必须仅通过加密连接发送"),
        TRUSTED_PROXIES("可信代理必须逐一填写实际地址，IPv4 使用 /32，IPv6 使用 /128，不能配置整个网络或任意正则表达式"),
        MAIL_HOST("必须配置邮件发送服务器"),
        MAIL_FROM("发件人地址必须是合法邮箱"),
        SIGNING_KEY("必须能取得请求签名密钥"),
        ENCRYPTION_KEY("必须能取得内容加密密钥的当前版本");

        private final String requirement;

        Check(String requirement) {
            this.requirement = requirement;
        }

        public String requirement() {
            return requirement;
        }
    }

    public ProductionSettingsValidation(Environment environment, ApplicationSecretKeys keys) {
        var problems = new ArrayList<String>();
        checkPublicOrigin(environment, problems);
        checkSessionCookie(environment, problems);
        checkTrustedProxies(environment, problems);
        checkMail(environment, problems);
        checkSecretKeys(keys, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                "生产配置未就绪（共 " + problems.size() + " 项）：" + String.join("；", problems));
        }
    }

    private void checkPublicOrigin(Environment environment, List<String> problems) {
        String publicBaseUrl = required(environment, "agenteam.web.public-base-url", problems);
        if (publicBaseUrl == null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(publicBaseUrl);
        } catch (IllegalArgumentException exception) {
            problems.add(failure(Check.PUBLIC_ORIGIN, publicBaseUrl));
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath()
            .isEmpty())) {
            problems.add(failure(Check.PUBLIC_ORIGIN, publicBaseUrl));
        }
    }

    private void checkSessionCookie(Environment environment, List<String> problems) {
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add(failure(Check.SESSION_COOKIE, "未开启"));
        }
    }

    private void checkTrustedProxies(Environment environment, List<String> problems) {
        String proxies = required(environment, "server.tomcat.remoteip.internal-proxies", problems);
        if (proxies == null) {
            return;
        }
        if (!Arrays.stream(proxies.split(",", -1)).allMatch(this::isSingleProxyAddress)) {
            problems.add(failure(Check.TRUSTED_PROXIES, proxies));
        }
    }

    private void checkMail(Environment environment, List<String> problems) {
        required(environment, "spring.mail.host", problems);
        String from = required(environment, "agenteam.mail.from", problems);
        if (from == null) {
            return;
        }
        try {
            AccountInputValidation.email(from, "from");
        } catch (RuntimeException exception) {
            problems.add(failure(Check.MAIL_FROM, from));
        }
    }

    private void checkSecretKeys(ApplicationSecretKeys keys, List<String> problems) {
        try {
            if (keys.requestKey() == null) {
                problems.add(failure(Check.SIGNING_KEY, "缺失"));
            }
        } catch (RuntimeException exception) {
            problems.add(failure(Check.SIGNING_KEY, exception.getMessage()));
        }
        try {
            keys.activeVersion();
        } catch (RuntimeException exception) {
            problems.add(failure(Check.ENCRYPTION_KEY, exception.getMessage()));
        }
    }

    private boolean isSingleProxyAddress(String configured) {
        String value = configured.trim();
        boolean ipv4 = value.matches("[0-9]+(?:\\.[0-9]+){3}/32");
        boolean ipv6 = value.matches("[0-9a-fA-F:]+/128") && value.contains(":");
        if (!ipv4 && !ipv6) {
            return false;
        }
        try {
            // 格式检查只允许地址字面量，不执行主机名解析。
            InetAddress address = InetAddress.getByName(value.substring(0, value.indexOf('/')));
            return !address.isAnyLocalAddress() && !address.isMulticastAddress() && address.getAddress().length == (ipv4 ? 4 : 16);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private String required(Environment environment, String name, List<String> problems) {
        String value;
        try {
            value = environment.getRequiredProperty(name);
        } catch (IllegalStateException exception) {
            problems.add("缺少生产配置：" + name);
            return null;
        }
        if (value.isBlank()) {
            problems.add("缺少生产配置：" + name);
            return null;
        }
        return value;
    }

    private String failure(Check check, String actual) {
        return check.name() + " 未通过（要求：" + check.requirement() + "，实际：" + actual + "）";
    }
}
