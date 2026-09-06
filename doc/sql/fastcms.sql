/*
Navicat MySQL Data Transfer

Source Server         : localhost
Source Server Version : 50729
Source Host           : 127.0.0.1:3306
Source Database       : fastcms

Target Server Type    : MYSQL
Target Server Version : 50729
File Encoding         : 65001

Date: 2021-06-22 22:43:18
*/

drop database fastcms;
create database fastcms default character set utf8mb4 collate utf8mb4_general_ci;

use fastcms;

SET NAMES utf8mb4;
SET character_set_client=utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

-- ----------------------------
-- Table structure for article
-- ----------------------------
DROP TABLE IF EXISTS `article`;
CREATE TABLE `article` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `content_html` longtext,
  `summary` varchar(255) DEFAULT NULL,
  `seo_keywords` varchar(255) DEFAULT NULL,
  `seo_description` varchar(255) DEFAULT NULL,
  `out_link` varchar(255) DEFAULT NULL COMMENT '文章外链',
  `sort_num` int(11) DEFAULT '0' COMMENT '文章排序，值越大越靠前',
  `view_count` int(11) DEFAULT '0' COMMENT '浏览量',
  `comment_enable` tinyint(4) DEFAULT '1' COMMENT '是否开启评论',
  `thumbnail` varchar(255) DEFAULT NULL COMMENT '文章缩略图',
  `status` varchar(32) DEFAULT NULL,
  `suffix` varchar(32) DEFAULT NULL COMMENT '页面后缀',
  `json_ext` text COMMENT 'json格式的扩展字段',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  `version` int(11) DEFAULT '0' COMMENT '乐观锁',
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for article_category
-- ----------------------------
DROP TABLE IF EXISTS `article_category`;
CREATE TABLE `article_category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL,
  `parent_id` bigint(20) DEFAULT '0' COMMENT '上级分类id',
  `title` varchar(255) NOT NULL,
  `type` varchar(32) DEFAULT NULL,
  `sort_num` int(11) DEFAULT '0',
  `icon` varchar(255) DEFAULT NULL,
  `suffix` varchar(32) DEFAULT NULL COMMENT '页面后缀',
  `path` varchar(32) DEFAULT NULL COMMENT '访问路径',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for article_category_relation
-- ----------------------------
DROP TABLE IF EXISTS `article_category_relation`;
CREATE TABLE `article_category_relation` (
  `article_id` bigint(20) NOT NULL,
  `category_id` bigint(20) NOT NULL,
  PRIMARY KEY (`article_id`,`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for article_tag
-- ----------------------------
CREATE TABLE `article_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL,
  `tag_name` varchar(255) NOT NULL,
  `type` varchar(32) DEFAULT NULL,
  `sort_num` int(11) DEFAULT '0',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for article_tag_relation
-- ----------------------------
CREATE TABLE `article_tag_relation` (
  `article_id` bigint(20) NOT NULL,
  `tag_id` bigint(20) NOT NULL,
  PRIMARY KEY (`article_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for article_comment
-- ----------------------------
DROP TABLE IF EXISTS `article_comment`;
CREATE TABLE `article_comment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL,
  `parent_id` bigint(20) DEFAULT '0',
  `article_id` bigint(20) DEFAULT NULL,
  `content` varchar(255) DEFAULT NULL,
  `sort_num` int(11) DEFAULT '0',
  `reply_count` int(11) DEFAULT '0' COMMENT '回复数',
  `up_count` int(11) DEFAULT '0' COMMENT '点赞数',
  `down_count` int(11) DEFAULT '0' COMMENT '踩赞数',
  `status` varchar(32) DEFAULT NULL COMMENT '评论状态 ',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `parent_id` (`parent_id`),
  KEY `article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for attachment
-- ----------------------------
DROP TABLE IF EXISTS `attachment`;
CREATE TABLE `attachment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '上传人id',
  `file_name` varchar(255) DEFAULT NULL COMMENT '原始文件名',
  `file_desc` varchar(255) DEFAULT NULL COMMENT '文件描述',
  `file_path` varchar(255) DEFAULT NULL COMMENT '文件相对路径',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件表';

-- ----------------------------
-- Table structure for config
-- ----------------------------
DROP TABLE IF EXISTS `config`;
CREATE TABLE `config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `key` varchar(32) DEFAULT NULL COMMENT '配置key键值',
  `value` text DEFAULT NULL COMMENT '配置值',
  PRIMARY KEY (`id`),
  KEY `key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';


-- ----------------------------
-- Table structure for menu
-- ----------------------------
DROP TABLE IF EXISTS `menu`;
CREATE TABLE `menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT '0',
  `user_id` bigint(20) DEFAULT NULL,
  `menu_name` varchar(32) DEFAULT NULL,
  `menu_url` varchar(255) DEFAULT NULL,
  `menu_icon` varchar(255) DEFAULT NULL,
  `sort_num` int(11) DEFAULT '0',
  `target` varchar(32) DEFAULT '_self',
  `status` varchar(32) DEFAULT 'show' COMMENT '显示或隐藏',
  `template_id` varchar(64) DEFAULT NULL COMMENT '专属模板ID（NULL=全局菜单）',
  `exclude_template_ids` varchar(500) DEFAULT NULL COMMENT '排除显示的模板ID列表，逗号分隔（仅全局菜单生效）',
  `exclude_site_keys` varchar(1000) DEFAULT NULL COMMENT '排除显示的站点key列表（域名或路径），逗号分隔（仅全局菜单生效）',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网站菜单表';

-- ----------------------------
-- Table structure for order
-- ----------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_sn` varchar(128) NOT NULL DEFAULT '' COMMENT '订单号',
  `user_id` bigint(20) NOT NULL COMMENT '购买人',
  `order_title` varchar(255) DEFAULT NULL,
  `buyer_msg` varchar(512) DEFAULT NULL COMMENT '用户留言',
  `order_amount` decimal(10,2) DEFAULT NULL COMMENT '订单商品金额之和',
  `pay_status` tinyint(2) DEFAULT NULL COMMENT '支付状态',
  `payment_id` bigint(20) DEFAULT NULL COMMENT '支付记录',
  `delivery_id` bigint(20) DEFAULT NULL COMMENT '发货情况',
  `delivery_status` tinyint(2) DEFAULT NULL COMMENT '1待发货，2已发货',
  `consignee_username` varchar(64) DEFAULT NULL COMMENT '收货人地址',
  `consignee_mobile` varchar(32) DEFAULT NULL COMMENT '收货人手机号（电话）',
  `consignee_addr_detail` varchar(256) DEFAULT NULL COMMENT '收件人的详细地址',
  `invoice_id` int(11) unsigned DEFAULT NULL COMMENT '发票',
  `invoice_status` tinyint(2) DEFAULT NULL COMMENT '发票开具状态：1 未申请发票、 2 发票申请中、 3 发票开具中、 4 无需开具发票、 5发票已经开具',
  `postage_amount` decimal(10,2) DEFAULT NULL COMMENT '订单邮费',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '支付金额，商品金额 + 邮费 - 优惠或减免金额',
  `remarks` text COMMENT '管理员后台备注',
  `trade_status` tinyint(2) DEFAULT NULL COMMENT '交易状态：1交易中、 2交易完成（但是可以申请退款） 、3取消交易 、4申请退款、 5拒绝退款、 6退款中、 7退款完成、 8交易结束',
  `version` int(11) DEFAULT '0',
  `status` tinyint(2) DEFAULT '1' COMMENT '删除状态：1 正常 ，0 已经删除',
  `created` datetime DEFAULT NULL COMMENT '创建时间',
  `updated` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `order_sn` (`order_sn`),
  KEY `user_id` (`user_id`),
  KEY `payment_id` (`payment_id`),
  KEY `user_status` (`user_id`,`trade_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';


-- ----------------------------
-- Table structure for order_invoice
-- ----------------------------
DROP TABLE IF EXISTS `order_invoice`;
CREATE TABLE `order_invoice` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `type` varchar(32) DEFAULT NULL COMMENT '发票类型',
  `title` varchar(128) DEFAULT NULL COMMENT '发票抬头',
  `content` varchar(128) DEFAULT NULL COMMENT '发票内容',
  `identity` varchar(32) DEFAULT NULL COMMENT '纳税人识别号',
  `name` varchar(128) DEFAULT NULL COMMENT '单位名称',
  `mobile` varchar(32) DEFAULT NULL COMMENT '发票收取人手机号',
  `email` varchar(32) DEFAULT NULL COMMENT '发票收取人邮箱',
  `status` tinyint(2) DEFAULT NULL COMMENT '发票状态',
  `updated` datetime DEFAULT NULL COMMENT '修改时间',
  `created` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票信息表';

-- ----------------------------
-- Table structure for order_item
-- ----------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_id` bigint(20) NOT NULL COMMENT '订单id',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '订单号',
  `seller_id` bigint(20) DEFAULT NULL COMMENT '卖家id',
  `product_id` bigint(20) DEFAULT NULL COMMENT '产品id',
  `product_type` varchar(64) DEFAULT NULL COMMENT '产品类型',
  `product_count` int(11) DEFAULT NULL COMMENT '产品数量',
  `postage_cost` decimal(10,2) DEFAULT NULL COMMENT '邮费',
  `total_amount` decimal(10,2) DEFAULT NULL COMMENT '具体金额 = 产品价格+运费+其他价格',
  `updated` datetime DEFAULT NULL COMMENT '修改时间',
  `created` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `order_id` (`order_id`),
  KEY `order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- ----------------------------
-- Table structure for payment_record
-- ----------------------------
DROP TABLE IF EXISTS `payment_record`;
CREATE TABLE `payment_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `product_relative_id` bigint(20) DEFAULT NULL COMMENT '相关产品ID',
  `trx_no` varchar(50) NOT NULL COMMENT '支付流水号',
  `trx_type` varchar(30) DEFAULT NULL COMMENT '交易业务类型  ：消费、充值等',
  `trx_nonce_str` varchar(64) DEFAULT NULL COMMENT '签名随机字符串，一般是用来防止重放攻击',
  `user_id` bigint(20) DEFAULT NULL COMMENT '付款人编号',
  `payer_fee` decimal(20,6) DEFAULT '0.000000' COMMENT '付款方手续费',
  `order_ip` varchar(30) DEFAULT NULL COMMENT '下单ip(客户端ip,从网关中获取)',
  `order_from` varchar(30) DEFAULT NULL COMMENT '订单来源',
  `pay_status` tinyint(2) DEFAULT NULL COMMENT '支付状态：0生成订单未支付（预支付）、1支付成功、 2支付失败',
  `pay_type` varchar(50) DEFAULT NULL COMMENT '支付类型编号',
  `pay_bank_type` varchar(128) DEFAULT NULL COMMENT '支付银行类型',
  `pay_amount` decimal(20,6) DEFAULT '0.000000' COMMENT '订单金额',
  `pay_success_amount` decimal(20,6) DEFAULT NULL COMMENT '成功支付金额',
  `pay_success_time` datetime DEFAULT NULL COMMENT '支付成功时间',
  `thirdparty_type` varchar(32) DEFAULT NULL COMMENT '第三方支付平台',
  `thirdparty_appid` varchar(32) DEFAULT NULL COMMENT '微信appid 或者 支付宝的appid，thirdparty 指的是支付的第三方比如微信、支付宝、PayPal等',
  `thirdparty_mch_id` varchar(32) DEFAULT NULL COMMENT '商户号',
  `thirdparty_trade_type` varchar(16) DEFAULT NULL COMMENT '交易类型',
  `thirdparty_transaction_id` varchar(32) DEFAULT NULL,
  `thirdparty_user_openid` varchar(64) DEFAULT NULL,
  `remark` text COMMENT '备注',
  `updated` datetime DEFAULT NULL,
  `created` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `trx_no` (`trx_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付记录表';

-- ----------------------------
-- Table structure for permission
-- ----------------------------
DROP TABLE IF EXISTS `permission`;
CREATE TABLE `permission` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT '0' COMMENT '父节点id',
  `name` varchar(32) DEFAULT NULL,
  `path` varchar(64) DEFAULT NULL,
  `component` varchar(128) DEFAULT NULL,
  `title` varchar(32) DEFAULT NULL,
  `icon` varchar(128) DEFAULT NULL,
  `is_link` tinyint(1) DEFAULT '0',
  `is_hide` tinyint(1) DEFAULT '0',
  `is_keep_alive` tinyint(1) DEFAULT '0',
  `is_affix` tinyint(1) DEFAULT '0',
  `is_iframe` tinyint(1) DEFAULT '0',
  `sort_num` int(11) DEFAULT '0',
  `category` varchar(16) DEFAULT NULL,
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';


-- ----------------------------
-- Table structure for role
-- ----------------------------
DROP TABLE IF EXISTS `role`;
CREATE TABLE `role` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `role_name` varchar(128) DEFAULT NULL COMMENT '角色名称',
  `role_desc` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  `active` int(4) DEFAULT '1' COMMENT '是否启用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ----------------------------
-- Table structure for role_permission
-- ----------------------------
DROP TABLE IF EXISTS `role_permission`;
CREATE TABLE `role_permission` (
  `role_id` bigint(20) NOT NULL COMMENT '角色id',
  `permission_id` bigint(20) NOT NULL COMMENT '权限id',
  PRIMARY KEY (`role_id`,`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- ----------------------------
-- Table structure for single_page
-- ----------------------------
DROP TABLE IF EXISTS `single_page`;
CREATE TABLE `single_page` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(20) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL COMMENT '标题',
  `path` varchar(32) DEFAULT NULL,
  `content_html` longtext COMMENT '内容',
  `out_link` varchar(512) DEFAULT NULL COMMENT '链接',
  `seo_keywords` varchar(256) DEFAULT NULL COMMENT 'SEO关键字',
  `seo_description` varchar(256) DEFAULT NULL COMMENT 'SEO描述信息',
  `summary` varchar(255) DEFAULT NULL COMMENT '摘要',
  `thumbnail` varchar(128) DEFAULT NULL COMMENT '缩略图',
  `style` varchar(32) DEFAULT NULL COMMENT '样式',
  `status` varchar(32) DEFAULT 'publish' COMMENT '状态',
  `suffix` varchar(32) DEFAULT NULL COMMENT '页面后缀',
  `view_count` int(11) unsigned DEFAULT '0' COMMENT '访问量',
  `comment_enable` tinyint(4) DEFAULT NULL,
  `created` datetime DEFAULT NULL COMMENT '创建日期',
  `updated` datetime DEFAULT NULL COMMENT '最后更新日期',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单页表';

-- ----------------------------
-- Table structure for single_page_comment
-- ----------------------------
DROP TABLE IF EXISTS `single_page_comment`;
CREATE TABLE `single_page_comment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `parent_id` bigint(20) unsigned DEFAULT NULL COMMENT '回复的评论ID',
  `page_id` bigint(20) unsigned DEFAULT NULL COMMENT '评论的内容ID',
  `user_id` bigint(20) unsigned DEFAULT NULL COMMENT '评论的用户ID',
  `content` varchar(255) DEFAULT NULL COMMENT '评论的内容',
  `reply_count` int(11) unsigned DEFAULT '0' COMMENT '评论的回复数量',
  `sort_num` int(11) DEFAULT '0' COMMENT '排序编号，常用语置顶等',
  `status` varchar(32) DEFAULT NULL COMMENT '评论的状态',
  `created` datetime DEFAULT NULL COMMENT '评论的时间',
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `page_id` (`page_id`),
  KEY `user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页面评论表';

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_name` varchar(32) DEFAULT NULL COMMENT '真实名称',
  `nick_name` varchar(32) DEFAULT NULL COMMENT '用户昵称',
  `email` varchar(128) DEFAULT NULL,
  `head_img` varchar(255) DEFAULT NULL,
  `mobile` varchar(32) DEFAULT NULL COMMENT '手机号码',
  `address` varchar(255) DEFAULT NULL,
  `company` varchar(255) DEFAULT NULL,
  `sex` tinyint(4) DEFAULT '1' COMMENT '1男0女',
  `source` varchar(64) DEFAULT NULL COMMENT '来源',
  `password` varchar(256) DEFAULT NULL COMMENT '登录密码',
  `salt` varchar(64) DEFAULT NULL COMMENT '加密盐值',
  `status` tinyint(4) DEFAULT '1' COMMENT '0禁用1正常',
  `login_time` datetime DEFAULT NULL COMMENT '最近登录时间',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  `version` int(11) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ----------------------------
-- Table structure for user_openid
-- ----------------------------
DROP TABLE IF EXISTS `user_openid`;
CREATE TABLE `user_openid` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '第三方类型：wechat，dingding，qq...',
  `value` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '第三方的openId的值',
  `version` int(11) DEFAULT '0',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `type_value` (`type`,`value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号绑定信息表';

-- ----------------------------
-- Table structure for user_role
-- ----------------------------
DROP TABLE IF EXISTS `user_role`;
CREATE TABLE `user_role` (
  `user_id` bigint(20) NOT NULL COMMENT '用户id',
  `role_id` bigint(20) NOT NULL COMMENT '角色id',
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- ----------------------------
-- Table structure for user_tag
-- ----------------------------
DROP TABLE IF EXISTS `user_tag`;
CREATE TABLE `user_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(255) DEFAULT NULL,
  `desc` varchar(255) DEFAULT NULL,
  `sort_num` int(11) DEFAULT NULL,
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for user_tag_relation
-- ----------------------------
DROP TABLE IF EXISTS `user_tag_relation`;
CREATE TABLE `user_tag_relation` (
  `user_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tag_id` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


INSERT INTO `role` (`id`, `role_name`, `role_desc`, `created`, `updated`, `active`) VALUES ('1', '超级管理员', '超级管理员角色是系统内置角色，拥有系统最大权限', NOW(), NOW(), 1);
INSERT INTO `user` (`id`, `user_name`, `nick_name`, `password`, `salt`, `created`, `updated`) VALUES ('1', 'admin', 'admin', '$2a$10$Lpudyy6BI./H9UJc9eIPjuflK4g.A.CnwCb1qgE2PGbWyjv2yDfbq', '1622734716287', NOW(), NOW());
INSERT INTO `user_role` (`user_id`, `role_id`) VALUES ('1', '1');

INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('1', '0', 'home', '/home', 'home/index', 'message.router.home', 'iconfont icon-shouye', '0', '0', '1', '1', '0', '0', 'admin', '2021-10-31 23:48:10', '2022-02-19 13:30:50');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('2', '0', 'system', '/system', 'layout/routerView/parent', 'message.router.system', 'iconfont icon-xitongshezhi', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-07 18:16:47', '2022-02-19 12:42:14');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('3', '2', 'systemMenu', '/system/menu', 'system/menu/index', 'message.router.systemMenu', 'iconfont icon-caidan', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-07 18:18:00', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('4', '2', 'systemRole', '/system/role', 'system/role/index', 'message.router.systemRole', 'el-icon-s-custom', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-08 12:00:50', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('5', '2', 'systemUser', '/system/user', 'system/user/index', 'message.router.systemUser', 'el-icon-user-solid', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-08 12:02:28', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('6', '0', 'attach', '/attach', 'layout/routerView/parent', 'message.router.attach', 'el-icon-picture', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:05:27', '2022-02-19 12:42:27');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('7', '6', 'attachManager', '/attach/index', 'attach/index', 'message.router.attachManager', 'el-icon-picture-outline', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:11:16', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('8', '6', 'attachSet', '/attach/set', 'attach/set', 'message.router.attachSet', 'el-icon-s-tools', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:12:33', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('9', '0', 'plugin', '/plugin', 'layout/routerView/parent', 'message.router.plugin', 'el-icon-s-home', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:14:05', '2022-02-19 12:42:45');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('10', '9', 'pluginManager', '/plugin/index', 'plugin/index', 'message.router.pluginManager', 'el-icon-s-management', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:15:58', '2021-12-07 10:30:45');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('11', '0', 'article', '/article', 'layout/routerView/parent', 'message.router.article', 'el-icon-s-order', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:18:10', '2022-02-19 12:42:53');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('12', '11', 'articleManager', '/article/index', 'article/index', 'message.router.articleManager', 'el-icon-s-fold', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:18:50', '2021-11-23 15:33:39');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('13', '11', 'articleWrite', '/article/write', 'article/write', 'message.router.articleWrite', 'el-icon-edit', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:18:50', '2021-11-23 15:33:39');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('14', '11', 'articleCategory', '/article/category', 'article/category', 'message.router.articleCategory', 'el-icon-s-operation', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:20:16', '2021-12-11 16:36:37');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('15', '11', 'articleComment', '/article/comment', 'article/comment', 'message.router.articleComment', 'el-icon-chat-dot-square', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:21:14', '2021-12-11 16:36:58');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('16', '11', 'articleSet', '/article/set', 'article/set', 'message.router.articleSet', 'el-icon-setting', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:19:39', '2021-12-11 16:31:02');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('17', '0', 'page', '/page', 'layout/routerView/parent', 'message.router.page', 'el-icon-document-copy', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:22:10', '2022-02-19 12:42:59');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('18', '17', 'pageManager', '/page/index', 'page/index', 'message.router.pageManager', 'el-icon-document', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:23:22', '2021-12-07 10:31:22');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('19', '17', 'pageWrite', '/page/write', 'page/write', 'message.router.pageWrite', 'el-icon-edit-outline', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:25:56', '2021-12-11 16:37:58');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('20', '17', 'pageComment', '/page/comment', 'page/comment', 'message.router.pageComment', 'el-icon-chat-dot-square', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:25:56', '2021-12-11 16:37:58');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('21', '17', 'pageSet', '/page/set', 'page/set', 'message.router.pageSet', 'el-icon-setting', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:26:27', '2021-12-11 16:38:16');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('22', '0', 'template', '/template', 'layout/routerView/parent', 'message.router.template', 'el-icon-folder-opened', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:28:05', '2022-02-19 12:43:06');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('23', '22', 'templateManager', '/template/index', 'template/index', 'message.router.templateManager', 'el-icon-folder', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:28:39', '2021-12-07 10:32:31');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('24', '22', 'templateEdit', '/template/edit', 'template/edit', 'message.router.templateEdit', 'el-icon-folder-checked', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:29:13', '2021-12-07 10:33:00');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('25', '22', 'templateMenu', '/template/menu', 'template/menu', 'message.router.templateMenu', 'el-icon-notebook-2', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:29:49', '2021-12-10 19:31:26');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('26', '22', 'templateSet', '/template/set', 'template/set', 'message.router.templateSet', 'el-icon-setting', '0', '0', '0', '0', '0', '0', NULL, '2021-11-21 10:30:17', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('27', '0', 'setting', '/setting', 'layout/routerView/parent', 'message.router.setting', 'el-icon-s-tools', '0', '0', '0', '0', '0', '999', NULL, '2021-12-02 14:28:36', '2022-02-19 13:31:10');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('28', '27', 'websiteSet', '/setting/website', 'setting/website', 'message.router.websiteSet', 'el-icon-eleme', '0', '0', '0', '0', '0', '0', NULL, '2021-12-02 14:30:44', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('29', '0', 'order', '/order', 'layout/routerView/parent', 'message.router.order', 'el-icon-shopping-bag-1', '0', '0', '0', '0', '0', '0', NULL, '2022-02-19 11:30:56', '2022-02-19 12:43:11');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('30', '29', 'orderManager', '/order/index', 'order/index', 'message.router.orderManager', 'el-icon-postcard', '0', '0', '0', '0', '0', '0', NULL, '2022-02-19 11:33:21', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('31', '29', 'orderSet', '/order/set', 'order/set', 'message.router.orderSet', 'el-icon-setting', '0', '0', '0', '0', '0', '999', NULL, '2022-02-19 11:34:19', NULL);

-- ----------------------------
-- 表结构变更记录
-- ----------------------------

ALTER TABLE `article` ADD COLUMN `attach_id` bigint(20) DEFAULT NULL COMMENT '附件' AFTER `suffix`;

alter table `order` drop column `payment_id`;

ALTER TABLE `user` ADD COLUMN `autograph` varchar(1024) DEFAULT NULL COMMENT '个性签名' AFTER `company`;
ALTER TABLE `user` ADD COLUMN `access_ip` varchar(32) DEFAULT NULL COMMENT '登录IP' AFTER `login_time`;

ALTER TABLE `attachment` ADD COLUMN `file_type` varchar(32) DEFAULT NULL COMMENT '文件类型' AFTER `file_path`;

-- ----------------------------
-- 0.0.2 表结构变更记录开始
-- ----------------------------

INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES ('32', '27', 'wechatSet', '/setting/wechat', 'setting/wechat', 'message.router.wechatSet', 'el-icon-star-off', '0', '0', '0', '0', '0', '0', NULL, '2022-03-02 23:18:57', NULL);

INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES (33, 27, 'connectionSet', '/setting/connection', 'setting/connection', 'message.router.connectionSet', 'el-icon-phone-outline', 0, 0, 0, 0, 0, 0, NULL, '2022-03-22 20:22:34', '2022-03-22 20:23:09');

INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES (34, 2, 'systemDept', '/system/dept', 'system/dept/index', 'message.router.systemDept', 'el-icon-office-building', 0, 0, 0, 0, 0, 0, NULL, '2022-03-23 19:35:15', '2022-03-23 19:38:05');

CREATE TABLE `department` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT '0' COMMENT '上级部门',
  `dept_name` varchar(128) DEFAULT NULL COMMENT '部门名称',
  `dept_desc` varchar(255) DEFAULT NULL COMMENT '部门描述',
  `dept_phone` varchar(32) DEFAULT NULL COMMENT '联系电话',
  `dept_addr` varchar(255) DEFAULT NULL COMMENT '部门地址',
  `dept_leader` varchar(32) DEFAULT NULL COMMENT '部门负责人',
  `status` tinyint(4) DEFAULT '1' COMMENT '0，禁用，1启用',
  `sort_num` int(11) DEFAULT '0' COMMENT '排序',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `department_user` (
  `dept_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  PRIMARY KEY (`dept_id`,`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- 0.0.2 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.0.3 表结构变更记录开始
-- ----------------------------

CREATE TABLE `user_amount` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `amount` decimal(20,6) NOT NULL DEFAULT '0.000000',
  `version` int(11) DEFAULT '0',
  `updated` datetime DEFAULT NULL,
  `created` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户余额';

CREATE TABLE `user_amount_payout` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '申请提现用户',
  `user_real_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户的真实名字',
  `user_idcard` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户的身份证号码',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '提现金额',
  `pay_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提现类型',
  `pay_to` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提现账号：可能是微信的openId，可能是支付宝账号，可能是银行账号',
  `pay_success_proof` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提现成功证明，一般是转账截图',
  `fee` decimal(10,2) DEFAULT NULL COMMENT '提现手续费',
  `statement_id` bigint(20) DEFAULT NULL COMMENT '申请提现成功后会生成一个扣款记录',
  `status` tinyint(2) DEFAULT '0' COMMENT '状态',
  `remarks` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户备注',
  `feedback` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '回绝提现时给出原因',
  `audit_type` tinyint(2) DEFAULT '1' COMMENT '审核类型 1人工审核，0自动到账',
  `options` text COLLATE utf8mb4_unicode_ci,
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `userid` (`user_id`),
  KEY `status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提现申请表';

CREATE TABLE `user_amount_statement` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户',
  `action` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '金额变动方向 add, del',
  `action_type` int(11) DEFAULT NULL COMMENT '金额变得业务类型1，提现，2，余额支付 等其他业务类型',
  `action_desc` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '金额变动描述',
  `action_order_id` bigint(20) unsigned DEFAULT NULL COMMENT '相关的订单ID',
  `action_payment_id` bigint(20) unsigned DEFAULT NULL COMMENT '相关的支付ID',
  `old_amount` decimal(20,6) NOT NULL COMMENT '用户之前的余额',
  `change_amount` decimal(20,6) NOT NULL COMMENT '变动金额',
  `new_amount` decimal(20,6) NOT NULL COMMENT '变动之后的余额',
  `created` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `action` (`action`),
  KEY `action_type` (`action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户余额流水情况';

ALTER TABLE `order` ADD COLUMN `json_ext` text COMMENT 'JSON扩展' AFTER `version`;

INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES (35, 29, 'paymentManager', '/payment/index', 'payment/index', 'message.router.paymentManager', 'el-icon-goods', 0, 0, 0, 0, 0, 0, NULL, '2022-04-07 11:22:16', NULL);
INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES (36, 29, 'cashoutManager', '/cashout/index', 'cashout/index', 'message.router.cashoutManager', 'el-icon-files', 0, 0, 0, 0, 0, 0, NULL, '2022-04-07 11:25:14', NULL);

-- ----------------------------
-- 0.0.3 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.0.4 表结构变更记录开始
-- ----------------------------

ALTER TABLE `user` ADD COLUMN `real_name` varchar(32) DEFAULT NULL COMMENT '真实姓名' AFTER `nick_name`;

ALTER TABLE `user` ADD COLUMN `user_type` tinyint(4) DEFAULT '2' COMMENT '1 系统用户，2 用户' AFTER `access_ip`;
ALTER TABLE `user` ADD INDEX user_type (`user_type`);
update `user` set user_type = 1 where id = 1;
INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES (37, 0, 'user', '/user', 'layout/routerView/parent', 'message.router.user', 'el-icon-user', 0, 0, 0, 0, 0, 0, NULL, '2022-04-27 11:02:31', NULL);
INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES (38, 37, 'userManager', '/user/index', 'user/index', 'message.router.userManager', 'el-icon-user-solid', 0, 0, 0, 0, 0, 0, NULL, '2022-04-27 11:13:00', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
VALUES ('39', '2', 'systemRes', '/system/res', 'system/res/index', 'message.router.systemRes', 'el-icon-s-data', '0', '0', '0', '0', '0', '0', NULL, '2022-05-02 18:15:51', '2022-05-02 18:20:17');

CREATE TABLE `resource` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `resource_name` varchar(32) DEFAULT NULL COMMENT '资源名称',
  `resource_path` varchar(64) DEFAULT NULL COMMENT '资源路径',
  `action_type` varchar(16) DEFAULT 'r' COMMENT 'r读 w写',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口资源表';

CREATE TABLE `role_resource` (
  `role_id` bigint(20) NOT NULL,
  `resource_path` varchar(128) NOT NULL,
  PRIMARY KEY (`role_id`,`resource_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口资源关联表';

-- ----------------------------
-- 0.0.4 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.0.5 表结构变更记录开始
-- ----------------------------

ALTER TABLE `config` modify COLUMN `KEY` varchar(128);
INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES (40, 27, 'systemSet', '/setting/system', 'setting/system', 'message.router.systemSet', 'el-icon-lock', 0, 0, 0, 0, 0, 0, NULL, '2022-08-06 12:48:45', '2022-08-06 12:49:45');

ALTER TABLE article CHANGE user_id create_user_id bigint(20);
ALTER TABLE article_category CHANGE user_id create_user_id bigint(20);
ALTER TABLE article_tag CHANGE user_id create_user_id bigint(20);
ALTER TABLE attachment CHANGE user_id create_user_id bigint(20);
ALTER TABLE menu CHANGE user_id create_user_id bigint(20);
ALTER TABLE `order` CHANGE user_id create_user_id bigint(20);
ALTER TABLE payment_record CHANGE user_id create_user_id bigint(20);
ALTER TABLE single_page CHANGE user_id create_user_id bigint(20);
ALTER TABLE single_page_comment CHANGE user_id create_user_id bigint(20);
ALTER TABLE user_amount CHANGE user_id create_user_id bigint(20);
ALTER TABLE user_amount_payout CHANGE user_id create_user_id bigint(20);
ALTER TABLE user_amount_statement CHANGE user_id create_user_id bigint(20);
ALTER TABLE article_comment CHANGE user_id create_user_id bigint(20);

-- ----------------------------
-- 0.0.5 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.0.8 表结构变更记录开始
-- ----------------------------
ALTER TABLE `menu` ADD COLUMN `url_type` tinyint(4)  DEFAULT '0' COMMENT '1，文章，2，页面，3，分类， 4，标签';
-- ----------------------------
-- 0.0.8 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.0.9 表结构变更记录开始
-- ----------------------------
INSERT INTO `permission`(`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`)
 VALUES (41, 11, 'articleTag', '/article/tag', 'article/tag', 'message.router.articleTag', 'el-icon-price-tag', 0, 0, 0, 0, 0, 0, NULL, '2022-11-25 16:05:46', NULL);
ALTER TABLE `article_tag` ADD COLUMN `suffix` varchar(64) DEFAULT NULL;
ALTER TABLE `article_tag` ADD COLUMN `icon` varchar(255) DEFAULT NULL;
ALTER TABLE `resource` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
-- ----------------------------
-- 0.0.9 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.1.0 表结构变更记录开始
-- ----------------------------
alter  table  `user`  add  index  `user_name_index`  (`user_name`);
-- ----------------------------
-- 0.1.0 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.1.3 表结构变更记录开始
-- ----------------------------
ALTER TABLE `user` ADD COLUMN `error_count` int(11) DEFAULT '0';
-- ----------------------------
-- 0.1.3 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.1.5 表结构变更记录开始
-- ----------------------------
CREATE TABLE `article_zan` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `create_user_id` bigint(20) DEFAULT NULL,
  `article_id` bigint(20) DEFAULT NULL,
  `created` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4;

ALTER TABLE `article` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `article_category` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `article_comment` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `article_tag` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `single_page` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `single_page_comment` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';
ALTER TABLE `menu` ADD COLUMN `language` varchar(64) DEFAULT NULL COMMENT '语言';

delete from `permission`;
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('1', '0', 'home', '/home', 'home/index', 'message.router.home', 'iconfont icon-shouye', '0', '0', '1', '1', '0', '0', 'admin', '2021-10-31 23:48:10', '2022-02-19 13:30:50');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('2', '0', 'system', '/system', 'layout/routerView/parent', 'message.router.system', 'ele-Lollipop', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-07 18:16:47', '2023-05-16 00:15:02');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('3', '2', 'systemMenu', '/system/menu', 'system/menu/index', 'message.router.systemMenu', 'iconfont icon-caidan', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-07 18:18:00', NULL);
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('4', '2', 'systemRole', '/system/role', 'system/role/index', 'message.router.systemRole', 'ele-Avatar', '0', '0', '0', '0', '0', '0', 'admin', '2021-11-08 12:00:50', '2023-05-14 23:19:31');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('5', '2', 'systemUser', '/system/user', 'system/user/index', 'message.router.systemUser', 'ele-User', '0', '0', '0', '0', '0', '3', 'admin', '2021-11-08 12:02:28', '2023-05-16 00:03:53');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('6', '0', 'attach', '/attach', 'layout/routerView/parent', 'message.router.attach', 'ele-Folder', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:05:27', '2023-05-16 00:09:54');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('7', '6', 'attachManager', '/attach/index', 'attach/index', 'message.router.attachManager', 'ele-CopyDocument', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:11:16', '2023-05-16 00:09:01');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('8', '6', 'attachSet', '/attach/set', 'attach/set', 'message.router.attachSet', 'iconfont icon-quanjushezhi_o', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:12:33', '2023-05-16 00:13:44');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('9', '0', 'plugin', '/plugin', 'layout/routerView/parent', 'message.router.plugin', 'ele-Management', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:14:05', '2023-05-16 00:12:32');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('10', '9', 'pluginManager', '/plugin/index', 'plugin/index', 'message.router.pluginManager', 'ele-Connection', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:15:58', '2023-05-28 21:14:57');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('11', '0', 'article', '/article', 'layout/routerView/parent', 'message.router.article', 'ele-Document', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:18:10', '2023-05-28 21:15:14');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('12', '11', 'articleManager', '/article/index', 'article/index', 'message.router.articleManager', 'ele-DocumentCopy', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:18:50', '2023-05-28 21:15:41');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('13', '11', 'articleWrite', '/article/write', 'article/write', 'message.router.articleWrite', 'ele-DocumentAdd', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:18:50', '2023-05-28 21:15:54');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('14', '11', 'articleCategory', '/article/category', 'article/category', 'message.router.articleCategory', 'iconfont icon-juxingkaobei', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:20:16', '2023-05-28 21:16:15');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('15', '11', 'articleComment', '/article/comment', 'article/comment', 'message.router.articleComment', 'ele-Comment', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:21:14', '2023-05-28 21:16:32');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('16', '11', 'articleSet', '/article/set', 'article/set', 'message.router.articleSet', 'iconfont icon-quanjushezhi_o', '0', '0', '0', '0', '0', '990', '', '2021-11-21 10:19:39', '2023-05-28 21:17:51');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('17', '0', 'page', '/page', 'layout/routerView/parent', 'message.router.page', 'ele-DocumentCopy', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:22:10', '2023-05-28 21:18:49');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('18', '17', 'pageManager', '/page/index', 'page/index', 'message.router.pageManager', 'ele-Tickets', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:23:22', '2023-05-28 21:19:26');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('19', '17', 'pageWrite', '/page/write', 'page/write', 'message.router.pageWrite', 'ele-Edit', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:25:56', '2023-05-28 21:19:57');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('20', '17', 'pageComment', '/page/comment', 'page/comment', 'message.router.pageComment', 'ele-Comment', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:25:56', '2023-05-28 21:21:01');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('21', '17', 'pageSet', '/page/set', 'page/set', 'message.router.pageSet', 'ele-Setting', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:26:27', '2023-05-28 21:21:25');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('22', '0', 'template', '/template', 'layout/routerView/parent', 'message.router.template', 'ele-FolderOpened', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:28:05', '2023-05-28 21:24:26');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('23', '22', 'templateManager', '/template/index', 'template/index', 'message.router.templateManager', 'ele-Folder', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:28:39', '2023-05-28 21:25:38');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('24', '22', 'templateEdit', '/template/edit', 'template/edit', 'message.router.templateEdit', 'ele-EditPen', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:29:13', '2023-05-28 21:26:05');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('25', '22', 'templateMenu', '/template/menu', 'template/menu', 'message.router.templateMenu', 'ele-Memo', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:29:49', '2023-05-28 21:26:43');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('26', '22', 'templateSet', '/template/set', 'template/set', 'message.router.templateSet', 'ele-Setting', '0', '0', '0', '0', '0', '0', '', '2021-11-21 10:30:17', '2023-05-28 21:26:58');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('27', '0', 'setting', '/setting', 'layout/routerView/parent', 'message.router.setting', 'iconfont icon-quanjushezhi_o', '0', '0', '0', '0', '0', '999', '', '2021-12-02 14:28:36', '2023-05-16 00:14:06');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('28', '27', 'websiteSet', '/setting/website', 'setting/website', 'message.router.websiteSet', 'ele-Eleme', '0', '0', '0', '0', '0', '0', '', '2021-12-02 14:30:44', '2023-05-28 21:33:01');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('29', '0', 'order', '/order', 'layout/routerView/parent', 'message.router.order', 'ele-ShoppingCart', '0', '0', '0', '0', '0', '0', '', '2022-02-19 11:30:56', '2023-05-28 21:29:13');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('30', '29', 'orderManager', '/order/index', 'order/index', 'message.router.orderManager', 'ele-ShoppingTrolley', '0', '0', '0', '0', '0', '0', '', '2022-02-19 11:33:21', '2023-05-28 21:29:34');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('31', '29', 'orderSet', '/order/set', 'order/set', 'message.router.orderSet', 'ele-Setting', '0', '0', '0', '0', '0', '999', '', '2022-02-19 11:34:19', '2023-05-28 21:31:10');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('32', '27', 'wechatSet', '/setting/wechat', 'setting/wechat', 'message.router.wechatSet', 'ele-ChatRound', '0', '0', '0', '0', '0', '0', '', '2022-03-02 23:18:57', '2023-05-28 21:33:18');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('33', '27', 'connectionSet', '/setting/connection', 'setting/connection', 'message.router.connectionSet', 'ele-Phone', '0', '0', '0', '0', '0', '0', '', '2022-03-22 20:22:34', '2023-05-28 21:33:41');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('34', '2', 'systemDept', '/system/dept', 'system/dept/index', 'message.router.systemDept', 'iconfont icon-shuxingtu', '0', '0', '0', '0', '0', '0', '', '2022-03-23 19:35:15', '2023-05-16 00:04:23');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('35', '29', 'paymentManager', '/payment/index', 'payment/index', 'message.router.paymentManager', 'ele-ScaleToOriginal', '0', '0', '0', '0', '0', '0', '', '2022-04-07 11:22:16', '2023-05-28 21:29:55');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('36', '29', 'cashoutManager', '/cashout/index', 'cashout/index', 'message.router.cashoutManager', 'ele-Finished', '0', '0', '0', '0', '0', '0', '', '2022-04-07 11:25:14', '2023-05-28 21:30:50');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('37', '0', 'user', '/user', 'layout/routerView/parent', 'message.router.user', 'ele-Avatar', '0', '0', '0', '0', '0', '0', '', '2022-04-27 11:02:31', '2023-05-28 21:31:34');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('38', '37', 'userManager', '/user/index', 'user/index', 'message.router.userManager', 'ele-User', '0', '0', '0', '0', '0', '0', '', '2022-04-27 11:13:00', '2023-05-28 21:31:55');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('39', '2', 'systemRes', '/system/res', 'system/res/index', 'message.router.systemRes', 'iconfont icon-zidingyibuju', '0', '0', '0', '0', '0', '0', '', '2022-05-02 18:15:51', '2023-05-16 00:07:32');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('40', '27', 'systemSet', '/setting/system', 'setting/system', 'message.router.systemSet', 'ele-Promotion', '0', '0', '0', '0', '0', '2', '', '2022-08-06 12:48:45', '2023-05-28 21:34:38');
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('41', '11', 'articleTag', '/article/tag', 'article/tag', 'message.router.articleTag', 'ele-PriceTag', '0', '0', '0', '0', '0', '0', '', '2022-11-25 16:05:46', '2023-05-28 21:16:59');
-- ----------------------------
-- 0.1.5 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 1.0.0 表结构变更记录开始
-- ----------------------------
CREATE TABLE `user_server_openid` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `client_id` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
    `openid` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
    `sub` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'fastcms用户唯一标志',
    `created` timestamp NULL DEFAULT NULL,
    `updated` timestamp NULL DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
-- ----------------------------
-- 1.0.0 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.2.0 表结构变更记录开始
-- ----------------------------
DROP TABLE IF EXISTS `ai_model_config`;
CREATE TABLE `ai_model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '配置名称',
  `provider` varchar(32) DEFAULT NULL COMMENT '供应商: deepseek/qwen/zhipu/moonshot/openai/ollama/custom',
  `base_url` varchar(255) NOT NULL COMMENT 'OpenAI 兼容 API 端点',
  `api_key` varchar(255) DEFAULT NULL COMMENT 'API Key（Ollama 等本地调用可为空）',
  `model` varchar(64) NOT NULL COMMENT '模型名称',
  `temperature` double DEFAULT NULL COMMENT '温度参数',
  `max_tokens` int DEFAULT NULL COMMENT '最大 tokens',
  `is_active` tinyint(1) DEFAULT '0' COMMENT '是否激活（同一时刻仅一个为 true）',
  `sort_num` int DEFAULT '0' COMMENT '排序',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `extra_headers` text DEFAULT NULL COMMENT '自定义请求头 JSON，如 {"X-Tenant":"abc"}（私有部署/企业网关用）',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型配置表';

-- 设置菜单：模型管理（原 AI 菜单；AI 模板生成器已整合进模板编辑页，独立菜单下线）
INSERT INTO `permission` (`id`, `parent_id`, `name`, `path`, `component`, `title`, `icon`, `is_link`, `is_hide`, `is_keep_alive`, `is_affix`, `is_iframe`, `sort_num`, `category`, `created`, `updated`) VALUES ('43', '27', 'aiModel', '/setting/model', 'ai/model/index', 'message.router.aiModel', 'ele-Connection', '0', '0', '0', '0', '0', '1', '', NOW(), NULL);

-- AI 模板生成会话表
DROP TABLE IF EXISTS `ai_template_session`;
CREATE TABLE `ai_template_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话唯一ID（UUID）',
  `template_name` varchar(64) NOT NULL COMMENT '模板目录名（英文，作为 pathName）',
  `title` varchar(255) DEFAULT NULL COMMENT '会话标题',
  `requirement` text DEFAULT NULL COMMENT '用户初始需求描述',
  `status` varchar(16) DEFAULT 'active' COMMENT '会话状态: active/applied/closed',
  `user_id` bigint DEFAULT NULL COMMENT '创建用户ID',
  `work_dir` varchar(512) DEFAULT NULL COMMENT '会话工作目录绝对路径',
  `template_id` varchar(64) DEFAULT NULL COMMENT '绑定的正式模板ID（非空表示调整型会话，AI 输出直写正式模板目录）',
  `plan_files` text DEFAULT NULL COMMENT '分批流水线规划文件清单（JSON 数组，用于进度恢复与断点续传）',
  `mobile_adaptive` tinyint(1) DEFAULT 1 COMMENT '是否适配移动端（1=响应式布局，null 视为 1）',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模板生成会话表';

-- 已有环境升级（0.2.x → 0.3.x）：为 ai_template_session 增加规划清单字段
-- ALTER TABLE `ai_template_session` ADD COLUMN `plan_files` text DEFAULT NULL COMMENT '分批流水线规划文件清单（JSON 数组，用于进度恢复与断点续传）' AFTER `template_id`;
-- 已有环境升级（0.3.x）：为 ai_template_session 增加移动端适配选项字段
-- ALTER TABLE `ai_template_session` ADD COLUMN `mobile_adaptive` tinyint(1) DEFAULT 1 COMMENT '是否适配移动端（1=响应式布局，null 视为 1）' AFTER `plan_files`;

-- AI 模板生成对话消息表
DROP TABLE IF EXISTS `ai_template_message`;
CREATE TABLE `ai_template_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `role` varchar(16) NOT NULL COMMENT '角色: user/assistant/system',
  `content` mediumtext NOT NULL COMMENT '消息内容',
  `reasoning` mediumtext DEFAULT NULL COMMENT '推理模型思考过程',
  `created` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模板生成对话消息表';

-- AI 模板生成文件表（持久化会话生成的所有文件，便于跨重启恢复）
DROP TABLE IF EXISTS `ai_template_file`;
CREATE TABLE `ai_template_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `file_path` varchar(255) NOT NULL COMMENT '相对路径，如 index.html、static/css/base.css',
  `content` longtext NOT NULL COMMENT '文件内容',
  `action` varchar(16) DEFAULT 'create' COMMENT '操作类型: create/modify/delete',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_file` (`session_id`, `file_path`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模板生成文件表';

-- AI 模板文件修改前备份表（调整型会话：AI 修改正式模板文件前留存旧版本，支持按轮次回滚）
DROP TABLE IF EXISTS `ai_template_file_backup`;
CREATE TABLE `ai_template_file_backup` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `message_id` bigint NOT NULL COMMENT '触发本次变更的AI消息ID（回滚粒度）',
  `file_path` varchar(255) NOT NULL COMMENT '相对路径',
  `content` longtext COMMENT '修改前内容（修改前文件不存在时为 NULL）',
  `existed` tinyint DEFAULT '1' COMMENT '修改前文件是否存在（AI 新建的文件为 0）',
  `created` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_message_id` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模板文件修改前备份表';

-- ----------------------------
-- 0.2.0 表结构变更记录结束
-- ----------------------------

-- ----------------------------
-- 0.3.0 表结构变更记录开始
-- ----------------------------

-- 附件目录表（附件分类树：parent_id 父目录，0 为根）
DROP TABLE IF EXISTS `attachment_directory`;
CREATE TABLE `attachment_directory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父目录ID，0为根目录',
  `name` varchar(50) NOT NULL COMMENT '目录名称',
  `sort_num` int DEFAULT 0 COMMENT '排序（越小越靠前）',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件目录表';

-- 已有环境升级（0.2.x → 0.3.x）：附件表增加目录归属字段
-- ALTER TABLE `attachment` ADD COLUMN `directory_id` bigint NOT NULL DEFAULT 0 COMMENT '所属目录ID，0为未分类' AFTER `file_type`;

-- AI 模型配置表增加用途场景（chat=对话，image=生图；同场景内仅一条激活）
-- ALTER TABLE `ai_model_config` ADD COLUMN `scene` varchar(20) NOT NULL DEFAULT 'chat' COMMENT '用途场景: chat-对话 image-生图' AFTER `model`;

-- AI 生图任务表（文生图/修图异步任务，前端轮询状态）
DROP TABLE IF EXISTS `ai_image_task`;
CREATE TABLE `ai_image_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) DEFAULT NULL COMMENT '关联模板会话ID，媒体库生图为 NULL',
  `user_id` bigint NOT NULL COMMENT '发起用户ID',
  `task_type` varchar(10) NOT NULL COMMENT '任务类型: t2i-文生图 edit-修图',
  `model` varchar(64) NOT NULL COMMENT '生图模型名称',
  `prompt` varchar(2000) NOT NULL COMMENT '提示词（业务语义描述）',
  `source_attachment_id` bigint DEFAULT NULL COMMENT '修图原图附件ID',
  `template_id` varchar(64) DEFAULT NULL COMMENT '修图源/回写目标模板ID（模板 static 图片修图场景）',
  `template_file_path` varchar(500) DEFAULT NULL COMMENT '修图源/回写目标模板内文件路径（回写前原图自动备份为 .bak）',
  `size` varchar(20) NOT NULL DEFAULT '1664*928' COMMENT '生成尺寸 宽*高',
  `num` int NOT NULL DEFAULT 1 COMMENT '生成张数 1-4',
  `status` varchar(10) NOT NULL DEFAULT 'pending' COMMENT '状态: pending/running/success/failed',
  `result_paths` text DEFAULT NULL COMMENT '结果 JSON: [{filePath,attachmentId}]',
  `error` varchar(500) DEFAULT NULL COMMENT '失败原因',
  `created` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 生图任务表';

-- 已有环境升级（0.3.x）：为 ai_image_task 增加模板图片修图字段
-- ALTER TABLE `ai_image_task` ADD COLUMN `template_id` varchar(64) DEFAULT NULL COMMENT '修图源/回写目标模板ID（模板 static 图片修图场景）' AFTER `source_attachment_id`;
-- ALTER TABLE `ai_image_task` ADD COLUMN `template_file_path` varchar(500) DEFAULT NULL COMMENT '修图源/回写目标模板内文件路径（回写前原图自动备份为 .bak）' AFTER `template_id`;

-- ----------------------------
-- 0.3.0 表结构变更记录结束
-- ----------------------------
