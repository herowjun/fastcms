CREATE TABLE IF NOT EXISTS `ai_subscription_license` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `license_code` text NOT NULL COMMENT '授权码原文 FCAI1.<载荷>.<签名>',
  `sub_id` varchar(64) DEFAULT NULL COMMENT '官网侧订阅/订单溯源 id',
  `plan` varchar(16) DEFAULT NULL COMMENT '订阅周期 month/quarter/year',
  `scope` varchar(255) DEFAULT NULL COMMENT '功能范围逗号分隔 template,article,image；空表示全部',
  `issued_at` datetime DEFAULT NULL COMMENT '官网签发时间',
  `expire_at` datetime NOT NULL COMMENT '到期时间',
  `machine` varchar(64) DEFAULT NULL COMMENT '绑定的机器指纹，空表示不限',
  `state` tinyint(2) DEFAULT '1' COMMENT '1 生效 0 已解绑',
  `base_url` varchar(255) DEFAULT NULL COMMENT '激活时写入的官网中转端点',
  `activated_at` datetime DEFAULT NULL COMMENT '本机激活时间',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_state_expire` (`state`, `expire_at`),
  KEY `idx_sub_id` (`sub_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户实例侧：AI 模型订阅授权（历史保留，state=1 且 expire_at 最大者为当前生效授权）';

CREATE TABLE IF NOT EXISTS `ai_subscription_grant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_sn` varchar(128) NOT NULL COMMENT '订单号',
  `order_item_id` bigint DEFAULT NULL COMMENT '订单明细 id',
  `buyer_id` bigint DEFAULT NULL COMMENT '购买人',
  `product_id` bigint DEFAULT NULL COMMENT '订阅商品（文章 id）',
  `plan` varchar(16) DEFAULT NULL COMMENT '订阅周期 month/quarter/year',
  `scope` varchar(255) DEFAULT NULL COMMENT '功能范围逗号分隔',
  `sub_id` varchar(64) DEFAULT NULL COMMENT '本次订阅 id，即授权码载荷中的 sid',
  `license_code` text NOT NULL COMMENT '签发的授权码',
  `issued_at` datetime DEFAULT NULL COMMENT '签发时间',
  `expire_at` datetime DEFAULT NULL COMMENT '到期时间',
  `state` tinyint(2) DEFAULT '1' COMMENT '1 有效 0 已作废（退款等）',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order_sn` (`order_sn`),
  KEY `idx_buyer` (`buyer_id`),
  KEY `idx_sub_id` (`sub_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官网实例侧：AI 模型订阅授权签发记录';
