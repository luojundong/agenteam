package com.stonewu.agenteam.configuration.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 非生产环境提示密钥来源，避免「本地能跑、上线炸」。
 *
 * <p>密钥缺失时会自动生成并落盘到应用目录（与数据库同机、无备份、多实例不共享）。
 * 本地开发这样最省事，但如果一路带到预发或生产，换机、扩容或重建容器就会解不开
 * 用旧密钥写入的内容，而部署时看不到任何征兆。这里在启动时明确说出来。
 *
 * <p>生产是否应当直接拒绝启动属于 P2-E 的范围，那里要连同备份与轮换一起定，
 * 因此本组件只在非生产环境生效，不改变任何环境的启动结果。
 */
@Component
@Profile("!production")
public class LocalSecretKeyNotice {

    private static final Logger log = LoggerFactory.getLogger(LocalSecretKeyNotice.class);

    public LocalSecretKeyNotice(ApplicationSecretKeys keys) {
        if (!keys.generatedLocally()) {
            return;
        }
        log.warn("当前未注入应用密钥，正在使用自动生成的本机密钥文件：仅可用于本地开发。"
            + "上线前必须通过 AGENTEAM_REQUEST_SIGNING_KEY 与 AGENTEAM_ENCRYPTION_KEYS 注入，"
            + "并妥善备份——更换机器或重建容器后，用旧密钥写入的内容将无法解开。");
    }
}
