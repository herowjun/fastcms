<template>
	<div class="login-container">
		<div class="login-panel">
			<!-- 左：深空蓝品牌区（CSS 动态视觉，无位图） -->
			<div class="login-left">
				<div class="orb orb-a"></div>
				<div class="orb orb-b"></div>
				<div class="logo">
					<div class="logo-mark">F</div>
					<div class="logo-name">FastCMS<small>AI-POWERED CONTENT</small></div>
				</div>
				<div class="login-headline">
					<h1>用一句话，<br />生成一个<em>会生长</em>的网站</h1>
					<p>AI 模板引擎 + 组件编排，从需求描述到可发布站点，分钟级交付；内容、订单、评论在同一后台统一管理。</p>
				</div>
				<div class="ai-demo">
					<div class="ai-demo-user">企业官网模板，蓝色调，响应式设计</div>
					<div class="ai-demo-ai">
						<div class="dot">F</div>
						<div class="body">
							正在生成 <b>my-company</b> 模板… 已编排 5/6 个组件（导航 · 首页 Hero · 产品 · 关于 · 联系），正在定向润色视觉细节。
							<div class="bar"><i></i></div>
							<div class="tip">预计还需 40 秒 · 生成完成后可在编辑器中继续对话调整</div>
						</div>
					</div>
				</div>
			</div>
			<!-- 右：白色表单区（登录逻辑不变，仅换皮肤） -->
			<div class="login-right">
				<div class="login-box">
					<h2>欢迎回来</h2>
					<p class="sub">登录你的 FastCMS 控制台</p>
					<div class="login-right-warp-mian">
							<div v-if="!state.isScan">
								<el-tabs v-model="state.tabsActiveName">
									<el-tab-pane :label="$t('message.label.one1')" name="account">
										<Account />
									</el-tab-pane>
									<el-tab-pane v-if="state.isEnableMobileLogin" :label="$t('message.label.two2')" name="mobile">
										<Mobile />
									</el-tab-pane>
								</el-tabs>
								<div class="login-links">
									<el-button link type="primary" @click="toRestPassword" v-if="state.public_reset_password_enable">{{ $t('message.link.two6') }}</el-button>
									<el-button link type="primary" @click="toRegister" v-if="state.public_register_enable">{{ $t('message.link.one3') }}</el-button>
									<el-button link type="primary" @click="toWechatMpOAuth" v-if="state.isWechatBrowser">{{ $t('message.link.two7') }}</el-button>
								</div>
							</div>
							<Scan v-if="state.isScan" />
						</div>
						<!-- 扫码登录切换：底部分割线 + 圆角按钮（原型 login-scan） -->
						<div v-if="state.isEnableScan" class="login-scan" @click="state.isScan = !state.isScan">
							<button type="button">{{ state.isScan ? '账号密码登录' : '微信扫码登录' }}</button>
						</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="loginIndex">
import { defineAsyncComponent, onMounted, reactive, computed } from 'vue';
import { storeToRefs } from 'pinia';
import { useThemeConfig } from '/@/stores/themeConfig';
import { NextLoading } from '/@/utils/loading';
import { useRouter } from 'vue-router';
import { ConfigApi } from '/@/api/config/index';
import qs from 'qs';

const router = useRouter();
const configApi = ConfigApi();
// 引入组件
const Account = defineAsyncComponent(() => import('/@/views/login/component/account.vue'));
const Mobile = defineAsyncComponent(() => import('/@/views/login/component/mobile.vue'));
const Scan = defineAsyncComponent(() => import('/@/views/login/component/scan.vue'));

// 定义变量内容
const storesThemeConfig = useThemeConfig();
const { themeConfig } = storeToRefs(storesThemeConfig);
const state = reactive({
	tabsActiveName: 'account',
	public_register_enable: false,
	public_reset_password_enable: false,
	isScan: false,
	isEnableMobileLogin: false,
	isEnableScan: false,
	isWechatBrowser: false,
	public_website_domain: '',
});

// 获取布局配置信息
const getThemeConfig = computed(() => {
	return themeConfig.value;
});

const toRegister = () => {
	router.push('/register');
}
const toRestPassword = () => {
	router.push('/rest/password');
}
const toWechatMpOAuth = () => {
	window.location.href = state.public_website_domain + "/oauth2/authorization/wechat-mp"
}
const judgeWechatBrowser = () => {
	const ua = window.navigator.userAgent.toLowerCase();
	const match = ua.match(/MicroMessenger/i);
	if (match === null) {
		return false;
	}
	if (match.includes('micromessenger')) {
		return true;
	}
	return false;
}

// 页面加载时
onMounted(() => {
	state.isWechatBrowser = judgeWechatBrowser();
	let paramKeys = new Array();
	paramKeys.push('public_website_domain');
	paramKeys.push('public_wechat_mp_scan_qrcode_login_enable');
	paramKeys.push('publicPhoneLoginEnable');
	paramKeys.push('public_register_enable');
	paramKeys.push('public_forgot_password_enable');
	let params = qs.stringify( {"configKeys" : paramKeys}, {arrayFormat: 'repeat'});
	configApi.getPublicConfigList(params).then((res) => {
		
		const b = res.data.map(obj => [obj.key, obj.jsonValue]);

		const map = new Map<string, { key: string; jsonValue: any }>(b);

		if(map.get("public_website_domain")) {
			state.public_website_domain = map.get("public_website_domain");
		}

		if(map.get("public_register_enable")) {
			state.public_register_enable = map.get("public_register_enable");
		}

		if(map.get("public_forgot_password_enable")) {
			state.public_reset_password_enable = map.get("public_forgot_password_enable");
		}

		if(map.get("public_wechat_mp_scan_qrcode_login_enable")) {
			state.isEnableScan = map.get("public_wechat_mp_scan_qrcode_login_enable");
		}

		if(map.get("publicPhoneLoginEnable")) {
			state.isEnableMobileLogin = map.get("publicPhoneLoginEnable");
		}
		
	})

	NextLoading.done();
});
</script>

<style scoped lang="scss">
// 深空蓝登录页：左深色品牌区 + 右白色表单（原型画框式：浅灰底 + 居中白色圆角卡片）
.login-container {
	min-height: 100vh;
	overflow: auto;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 40px 24px;
	background: var(--next-bg-main-color);
	.login-panel {
		width: 100%;
		max-width: 1280px;
		min-height: 620px;
		display: grid;
		grid-template-columns: 1.25fr 1fr;
		background: #fff;
		border-radius: 14px;
		overflow: hidden;
		box-shadow: 0 16px 48px rgba(15, 23, 42, 0.12);
	}
	.login-left {
		position: relative;
		overflow: hidden;
		display: flex;
		flex-direction: column;
		padding: 48px 44px;
		color: #e2e8f0;
		background: radial-gradient(1200px 600px at -10% -20%, #1e3a8a 0%, transparent 55%),
			radial-gradient(900px 500px at 110% 120%, #1e40af 0%, transparent 50%), #0b1020;
		&::before {
			// 细网格底纹
			content: '';
			position: absolute;
			inset: 0;
			background-image: linear-gradient(rgba(148, 163, 184, 0.07) 1px, transparent 1px),
				linear-gradient(90deg, rgba(148, 163, 184, 0.07) 1px, transparent 1px);
			background-size: 44px 44px;
			-webkit-mask-image: radial-gradient(700px 500px at 40% 30%, #000 30%, transparent 75%);
			mask-image: radial-gradient(700px 500px at 40% 30%, #000 30%, transparent 75%);
			pointer-events: none;
		}
		.orb {
			position: absolute;
			border-radius: 50%;
			filter: blur(70px);
			opacity: 0.5;
			pointer-events: none;
		}
		.orb-a {
			width: 340px;
			height: 340px;
			left: -90px;
			top: -70px;
			background: rgba(59, 130, 246, 0.55);
			animation: loginOrbDrift 14s ease-in-out infinite alternate;
		}
		.orb-b {
			width: 300px;
			height: 300px;
			right: -80px;
			bottom: -60px;
			background: rgba(37, 99, 235, 0.4);
			animation: loginOrbDrift 18s ease-in-out infinite alternate-reverse;
		}
		&>:not(.orb) {
			position: relative;
			z-index: 1;
		}
		.logo {
			display: flex;
			align-items: center;
			gap: 10px;
		}
		.logo-mark {
			width: 34px;
			height: 34px;
			border-radius: 9px;
			background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
			display: grid;
			place-items: center;
			font-weight: 800;
			font-size: 15px;
			color: #fff;
			box-shadow: 0 4px 14px rgba(37, 99, 235, 0.4);
		}
		.logo-name {
			font-size: 18px;
			font-weight: 700;
			color: #fff;
			letter-spacing: 0.02em;
			small {
				font-weight: 400;
				font-size: 11px;
				color: #a5b3c9;
				margin-left: 8px;
				letter-spacing: 0.08em;
			}
		}
		.login-headline {
			margin-top: 64px;
			h1 {
				font-size: 34px;
				line-height: 1.35;
				font-weight: 700;
				color: #f8fafc;
				letter-spacing: 0.01em;
			}
			h1 em {
				font-style: normal;
				background: linear-gradient(90deg, #60a5fa, #818cf8);
				-webkit-background-clip: text;
				background-clip: text;
				color: transparent;
			}
			p {
				margin-top: 12px;
				color: #aab8cc;
				font-size: 14.5px;
				max-width: 400px;
			}
		}
		// AI 对话示意卡（产品行为示意，非装饰）
		.ai-demo {
			margin-top: 36px;
			max-width: 420px;
			padding: 16px 18px;
			background: rgba(15, 23, 42, 0.55);
			border: 1px solid rgba(148, 163, 184, 0.18);
			border-radius: 12px;
			backdrop-filter: blur(6px);
		}
		.ai-demo-user {
			margin-left: auto;
			max-width: 78%;
			padding: 8px 12px;
			background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
			color: #fff;
			font-size: 13px;
			border-radius: 10px 10px 2px 10px;
		}
		.ai-demo-ai {
			margin-top: 12px;
			display: flex;
			gap: 10px;
			.dot {
				flex: none;
				width: 24px;
				height: 24px;
				border-radius: 7px;
				background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
				display: grid;
				place-items: center;
				font-size: 12px;
				font-weight: 700;
				color: #fff;
			}
			.body {
				padding: 9px 12px;
				background: rgba(30, 41, 59, 0.8);
				border: 1px solid rgba(148, 163, 184, 0.15);
				border-radius: 2px 10px 10px 10px;
				font-size: 12.5px;
				color: #cbd5e1;
				b {
					color: #93c5fd;
					font-weight: 600;
				}
			}
			.bar {
				margin-top: 8px;
				height: 4px;
				border-radius: 2px;
				background: rgba(148, 163, 184, 0.15);
				overflow: hidden;
				i {
					display: block;
					height: 100%;
					border-radius: 2px;
					background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
					animation: loginGenBar 2.4s ease-in-out infinite;
				}
			}
			.tip {
				margin-top: 6px;
				font-size: 11.5px;
				color: #64748b;
			}
		}
	}
	.login-right {
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 48px 44px;
		overflow: auto;
		background: #fff;
		.login-box {
			width: 100%;
			max-width: 340px;
			h2 {
				font-size: 22px;
				font-weight: 700;
				color: #0f172a;
			}
			.sub {
				margin-top: 6px;
				margin-bottom: 0;
				color: #94a3b8;
				font-size: 13px;
			}
		}
		.login-right-warp-mian {
			margin-top: 26px;
		}
		// 原型 login-tabs：下划线风（未选中 #94a3b8 14px，选中 #0f172a 600 + 2px 蓝条）
		:deep(.el-tabs) {
			.el-tabs__header {
				margin-bottom: 0;
			}
			.el-tabs__nav-wrap::after {
				height: 1px;
				background-color: #e2e8f0;
			}
			.el-tabs__active-bar {
				height: 2px;
				background-color: #3b82f6;
			}
			.el-tabs__item {
				height: auto;
				line-height: inherit;
				padding: 0 2px 10px;
				margin-right: 24px;
				font-size: 14px;
				color: #94a3b8;
				&.is-active {
					color: #0f172a;
					font-weight: 600;
				}
				&:hover {
					color: #0f172a;
				}
			}
		}
		// 原型 field input：44px 高 / 14px 字 / 8px 圆角 / #e2e8f0 边框 / focus 蓝边 + 3px 浅蓝光晕
		:deep(.el-input__wrapper) {
			height: 44px;
			border-radius: 8px;
			background: #fff;
			box-shadow: 0 0 0 1px #e2e8f0 inset;
			transition: box-shadow 0.2s;
			&:hover {
				box-shadow: 0 0 0 1px #e2e8f0 inset;
			}
			&.is-focus {
				box-shadow: 0 0 0 1px #3b82f6 inset, 0 0 0 3px rgba(59, 130, 246, 0.12);
			}
		}
		:deep(.el-input__inner) {
			height: 44px;
			font-size: 14px;
			color: #0f172a;
		}
		// 原型 login-btn：44px 高 / 8px 圆角 / 渐变底 / 15px 600 / letter-spacing .1em（穿透 account/mobile 子组件）
		:deep(.login-content-submit) {
			width: 100%;
			height: 44px;
			margin-top: 24px;
			border: none;
			border-radius: 8px;
			background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
			color: #fff;
			font-size: 15px;
			font-weight: 600;
			letter-spacing: 0.1em;
			box-shadow: 0 6px 18px rgba(37, 99, 235, 0.3);
			transition: all 0.2s;
			&:hover,
			&:focus {
				transform: translateY(-1px);
				box-shadow: 0 10px 24px rgba(37, 99, 235, 0.4);
			}
		}
		// 原型 login-links：两端对齐 / 13px / #94a3b8
		.login-links {
			margin-top: 18px;
			display: flex;
			justify-content: space-between;
			font-size: 13px;
			:deep(.el-button) {
				padding: 0;
				font-size: 13px;
				color: #94a3b8;
				transition: color 0.15s;
				&:hover {
					color: #3b82f6;
				}
			}
		}
		// 原型 login-scan：两侧分割线 + 白底圆角按钮
		.login-scan {
			margin-top: 26px;
			display: flex;
			align-items: center;
			gap: 10px;
			color: #94a3b8;
			font-size: 12px;
			&::before,
			&::after {
				content: '';
				flex: 1;
				height: 1px;
				background: #e2e8f0;
			}
			button {
				border: 1px solid #e2e8f0;
				background: #fff;
				border-radius: 99px;
				padding: 5px 14px;
				font-size: 12.5px;
				color: #475569;
				cursor: pointer;
				transition: all 0.15s;
				&:hover {
					border-color: #3b82f6;
					color: #3b82f6;
				}
			}
		}
	}
}
.login_likn_menu {
	z-index: 999;
}
@keyframes loginOrbDrift {
	from {
		transform: translate(0, 0);
	}
	to {
		transform: translate(40px, 28px);
	}
}
@keyframes loginGenBar {
	0% {
		width: 12%;
	}
	55% {
		width: 82%;
	}
	100% {
		width: 64%;
	}
}
@media (max-width: 960px) {
	// 原型响应式：单列堆叠、左侧保留（padding 收窄、标题上移）
	.login-container .login-panel {
		grid-template-columns: 1fr;
		min-height: 0;
	}
	.login-container .login-left {
		padding: 40px 32px;
	}
	.login-container .login-headline {
		margin-top: 36px;
	}
}
</style>
