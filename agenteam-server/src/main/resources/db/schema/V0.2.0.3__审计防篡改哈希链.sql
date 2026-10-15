-- P2-A：审计防篡改哈希链。
-- 目的：让 audit_event 的任意一条记录被篡改或删除都能被重算发现（合规审计基础能力）。
--
-- 设计要点：
-- 1) 每条记录保存 prev_hash（上一条的哈希）与 hash（本条哈希），哈希串联成链。
-- 2) 链按作用域（enterprise_id；全局身份动作用 '-'）分链，避免跨企业耦合与单链写入热点。
-- 3) 并发写入用链头表 audit_event_chain 的 SELECT ... FOR UPDATE 行锁串行化，杜绝链分叉。
-- 4) 存量行保持 NULL 表示"尚未封印"，由应用启动后按发生时间顺序追加到链尾，
--    不在此迁移里做 SQL 回填：SHA2() 无法表达递推依赖（hash_i 依赖 hash_i-1），
--    且 JSON 列在 SQL 与 Java 两侧的规范化结果不一致，交给应用侧用同一套算法封印。
ALTER TABLE `audit_event`
  ADD COLUMN `prev_hash` CHAR(64) NULL COMMENT '同作用域上一条记录的哈希，链首为空',
  ADD COLUMN `hash`      CHAR(64) NULL COMMENT '本记录防篡改哈希，尚未封印的历史记录为空';

CREATE TABLE `audit_event_chain`
(
  `scope_key`     VARCHAR(100) NOT NULL COMMENT '哈希链作用域：等于 enterprise_id，全局身份动作为 -',
  `last_event_id` VARCHAR(100) NOT NULL COMMENT '链尾事件编号',
  `last_hash`     CHAR(64)     NOT NULL COMMENT '链尾哈希',
  `updated_at`    DATETIME(3)  NOT NULL COMMENT '链尾更新时间',
  PRIMARY KEY (`scope_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计哈希链链头，写入时按作用域行锁串行化';
