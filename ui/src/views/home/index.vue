<template>
	<div class="home-container">
		<!-- AI 横幅：问候 + 进行中任务 + 真实入口 -->
		<div class="hero">
			<div class="hi">
				<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
					<path d="M12 2.5 14 9l6.5 2L14 13l-2 6.5L10 13l-6.5-2L10 9l2-6.5Z" />
				</svg>
			</div>
			<div class="hero-text">
				<h3>{{ currentTime }}，{{ userInfos.username === '' ? '游客' : userInfos.username }}</h3>
				<p>{{ aiRunningCount > 0 ? `${aiRunningCount} 个模板生成任务进行中，让 AI 继续完成它们` : '没有进行中的生成任务，用一句话开始一个新站点' }}</p>
			</div>
			<span class="sp"></span>
			<button class="go" @click="toAiCreate">
				AI 新建模板
				<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
					<path d="M5 12h14m-6-6 6 6-6 6" />
				</svg>
			</button>
		</div>
		<!-- 统计卡 -->
		<div class="stats">
			<div class="stat">
				<div class="t">
					<span class="ic">
						<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
							<path d="M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" />
							<path d="M9 8h6M9 12h6" />
						</svg>
					</span>今日新增文章
				</div>
				<div class="v" id="titleNum1">0</div>
				<div class="d"><span>总数</span><b id="tipNum1">0</b></div>
			</div>
			<div class="stat">
				<div class="t">
					<span class="ic">
						<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
							<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
							<circle cx="12" cy="12" r="3" />
						</svg>
					</span>今日浏览量
				</div>
				<div class="v" id="titleNum2">0</div>
				<div class="d"><span>总浏览量</span><b id="tipNum2">0</b></div>
			</div>
			<div class="stat">
				<div class="t">
					<span class="ic">
						<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
							<circle cx="9" cy="19" r="1.6" />
							<circle cx="17" cy="19" r="1.6" />
							<path d="M3 4h2l2.5 11.5a1.5 1.5 0 0 0 1.5 1.2h8.6a1.5 1.5 0 0 0 1.5-1.2L21 8H6" />
						</svg>
					</span>今日新增订单
				</div>
				<div class="v" id="titleNum3">0</div>
				<div class="d"><span>订单总数</span><b id="tipNum3">0</b></div>
			</div>
			<div class="stat">
				<div class="t">
					<span class="ic">
						<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
							<path d="M12 2.5 14 9l6.5 2L14 13l-2 6.5L10 13l-6.5-2L10 9l2-6.5Z" />
						</svg>
					</span>AI 模板（累计）
				</div>
				<div class="v" id="titleNum4">0</div>
				<div class="d">
					<template v-if="aiRunningCount > 0"><span class="up">● {{ aiRunningCount }} 生成中</span> ·</template>
					{{ state.aiAppliedCount }} 已应用
				</div>
			</div>
		</div>
		<!-- 下排：最近模板生成（真实数据）+ 文章浏览排行 -->
		<div class="grid2">
			<div class="card">
				<div class="card-h">
					<svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" class="card-h-ic-1">
						<path d="M12 2.5 14 9l6.5 2L14 13l-2 6.5L10 13l-6.5-2L10 9l2-6.5Z" />
					</svg>
					最近模板生成
					<span class="more" @click="toAiCreate">全部记录 →</span>
				</div>
				<div class="card-b">
					<div v-if="state.aiSessions.length === 0" class="empty">
						还没有生成记录，<a @click="toAiCreate">用一句话创建第一个模板 →</a>
					</div>
					<div class="gen-row" v-for="(s, k) in state.aiSessions" :key="s.sessionId || k" @click="toSessionDetail(s)">
						<div class="gen-ic" :class="genIconClass(s)">{{ genInitials(s) }}</div>
						<div class="gen-main">
							<b>{{ s.templateName || '未命名模板' }}</b>
							<span>{{ sessionModeLabel(s) }} · {{ formatCreated(s.created) }}</span>
						</div>
						<span class="st" :class="sessionStatusClass(s)"><i v-if="sessionStatusClass(s) === 'st-run'" class="pulse"></i>{{ sessionStatusLabel(s) }}</span>
					</div>
				</div>
			</div>
			<div class="card">
				<div class="card-h">
					<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" class="card-h-ic-2">
						<path d="M4 20V10M10 20V4M16 20v-7M21 20H3" />
					</svg>
					文章浏览排行
					<span class="more" @click="toArticle">文章管理 →</span>
				</div>
				<div class="card-b">
					<div v-if="state.articleListData.length === 0" class="empty">暂无浏览数据</div>
					<table v-else class="rank">
						<thead>
							<tr>
								<th style="width:44px">#</th>
								<th>标题</th>
								<th style="text-align:right">浏览</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="(v, k) in state.articleListData" :key="k">
								<td><i class="no">{{ k + 1 }}</i></td>
								<td><a class="tt" :href="v.url" target="_blank">{{ v.title }}</a></td>
								<td class="vv">{{ v.viewCount }}</td>
							</tr>
						</tbody>
					</table>
				</div>
			</div>
		</div>
	</div>
</template>

<script lang="ts" name="home" setup>
import { reactive, onMounted, computed } from 'vue';
import { CountUp } from 'countup.js';
import { useRouter } from 'vue-router';
import { formatAxis } from '/@/utils/formatTime';
import { storeToRefs } from 'pinia';
import { useUserInfo } from '/@/stores/userInfo';
import { IndexApi } from '/@/api/home/index';
import { AiTemplateApi } from '/@/api/ai/index';

const router = useRouter();
const indexApi = IndexApi();
const aiApi = AiTemplateApi();
const stores = useUserInfo();
// 获取用户信息 vuex
const { userInfos } = storeToRefs(stores);

const state = reactive({
	articleListData: [] as any[],
	aiSessions: [] as any[],
	aiTotal: 0,
	aiAppliedCount: 0,
});

// 当前时间提示语
const currentTime = computed(() => {
	return formatAxis(new Date());
});

// 生成中的任务数（active 状态 = 进行中）
const aiRunningCount = computed(() => {
	return state.aiSessions.filter((s) => s.status === 'active').length;
});

const toAiCreate = () => {
	router.push('/template/edit');
};
const toArticle = () => {
	router.push('/article');
};
const toSessionDetail = (s: any) => {
	// 有已应用的模板则打开该模板，否则打开新建页
	if (s.templateId) {
		router.push({ path: '/template/edit', query: { id: s.templateId } });
	} else {
		router.push('/template/edit');
	}
};

// 生成记录图标：按名称前两字符取缩写，配色轮询
const genInitials = (s: any) => {
	const name = s.templateName || 'AI';
	return name
		.replace(/[^a-zA-Z\u4e00-\u9fa5]/g, '')
		.slice(0, 2)
		.toUpperCase();
};
const genIconClass = (s: any) => {
	const idx = (s.sessionId || Math.random()).toString().split('').reduce((a: number, c: string) => a + c.charCodeAt(0), 0) % 3;
	return `g${idx + 1}`;
};
// 会话状态映射：applied=已应用 published=已发布 active=生成中 其余=已完成
const sessionStatusClass = (s: any) => {
	if (s.status === 'applied') return 'st-applied';
	if (s.status === 'published') return 'st-done';
	if (s.status === 'active') return 'st-run';
	return 'st-done';
};
// 生成模式标签（与后端 createMode 口径一致）：pipeline/未设置=组件编排；
// design=AI 自主设计；import=自主设计+参考文件（上传后血统归一）
const sessionModeLabel = (s: any) => {
	const mode = (s.createMode || '').toLowerCase();
	if (mode === 'design') return 'AI 自主设计';
	if (mode === 'import') return 'AI 自主设计 · 参考稿';
	return '组件编排';
};
const sessionStatusLabel = (s: any) => {
	if (s.status === 'applied') return '已应用';
	if (s.status === 'published') return '已发布';
	if (s.status === 'active') return '生成中';
	return '已完成';
};
const formatCreated = (t: string) => {
	if (!t) return '';
	const d = new Date(t);
	if (isNaN(d.getTime())) return t;
	const pad = (n: number) => (n < 10 ? '0' + n : '' + n);
	return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const getHomeData = () => {
	indexApi.getIndexData().then((res) => {
		let articleStat = res.data.articleStatData;
		let orderStat = res.data.orderStatData;
		new CountUp('titleNum1', 0).update(articleStat.todayCount);
		new CountUp('titleNum2', 0).update(articleStat.todayViewCount);
		new CountUp('titleNum3', 0).update(orderStat.todayCount);
		new CountUp('tipNum1', 0).update(articleStat.totalCount);
		new CountUp('tipNum2', 0).update(articleStat.totalViewCount);
		new CountUp('tipNum3', 0).update(orderStat.totalCount);
		state.articleListData = res.data.newArticleList;
	});
};

// 最近模板生成记录（真实数据，复用 AI 会话列表接口）
const getAiSessions = () => {
	aiApi
		.listSessions()
		.then((res) => {
			const list: any[] = res.data || [];
			state.aiTotal = list.length;
			// 已应用口径：applied=已应用，published=已应用并发布
			state.aiAppliedCount = list.filter((s: any) => s.status === 'applied' || s.status === 'published').length;
			new CountUp('titleNum4', 0).update(list.length);
			// 按创建时间倒序，取最近 4 条
			state.aiSessions = list
				.sort((a: any, b: any) => new Date(b.created).getTime() - new Date(a.created).getTime())
				.slice(0, 4);
		})
		.catch(() => {
			// AI 会话加载失败不影响首页其他数据
			state.aiSessions = [];
		});
};

// 页面加载时
onMounted(() => {
	getHomeData();
	getAiSessions();
});
</script>

<style scoped lang="scss">
.home-container {
	overflow-x: hidden;
	// 撑满视口剩余高度（main 区为内容高度，不随视口伸缩；56 顶栏 + 38 标签页 + 2px 余量 = 96px）
	height: calc(100vh - 96px);
	min-height: 560px;
	display: flex;
	flex-direction: column;
	// 页面内容统一上下左右边距（原型 .content padding:20px；border-box 下含于高度计算内）
	padding: 20px;
	// 下排两卡撑满剩余高度（高屏不留白）
	.grid2 {
		flex: 1;
		min-height: 300px;
	}
	// AI 横幅（白底卡片 + 渐变小面积点缀：左竖条/图标块/按钮，避免大面积深色压顶导致头重脚轻）
		.hero {
			position: relative;
			overflow: hidden;
			display: flex;
			align-items: center;
			gap: 18px;
			padding: 22px 26px;
			border-radius: 14px;
			background: #fff;
			border: 1px solid #e2e8f0;
			box-shadow: 0 8px 30px rgba(15, 23, 42, 0.06);
			// 左侧渐变竖条（呼应侧边栏激活项的渐变竖条样式）
			&::before {
				content: '';
				position: absolute;
				left: 0;
				top: 14px;
				bottom: 14px;
				width: 3px;
				border-radius: 0 3px 3px 0;
				background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
			}
			&>* {
				position: relative;
			}
			.hi {
				flex: none;
				width: 46px;
				height: 46px;
				display: grid;
				place-items: center;
				border-radius: 12px;
				background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
				color: #fff;
				box-shadow: 0 4px 12px rgba(37, 99, 235, 0.3);
			}
			.hero-text {
				min-width: 0;
				h3 {
					margin: 0;
					font-size: 17px;
					font-weight: 700;
					color: #0f172a;
				}
				p {
					margin: 2px 0 0;
					font-size: 12.5px;
					color: #64748b;
				}
			}
			.sp {
				flex: 1;
			}
			.go {
				flex: none;
				display: flex;
				align-items: center;
				gap: 8px;
				height: 38px;
				padding: 0 18px;
				border: none;
				border-radius: 99px;
				background: var(--grad, linear-gradient(135deg, #3b82f6 0%, #6366f1 100%));
				color: #fff;
				font-size: 13px;
				font-weight: 600;
				cursor: pointer;
				box-shadow: 0 3px 10px rgba(37, 99, 235, 0.3);
				transition: all 0.2s;
				&:hover {
					transform: translateY(-1px);
					box-shadow: 0 6px 16px rgba(37, 99, 235, 0.35);
				}
			}
		}
	// 统计卡
	.stats {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 14px;
		margin-top: 16px;
		.stat {
			padding: 16px 18px;
			background: #fff;
			border: 1px solid #e2e8f0;
			border-radius: 14px;
			box-shadow: 0 8px 30px rgba(15, 23, 42, 0.06);
			.t {
				display: flex;
				align-items: center;
				gap: 8px;
				font-size: 13px;
				color: #475569;
				// 原型 stat 图标：26px 浅蓝底圆角块 + 蓝色线性图标
				.ic {
					flex: none;
					width: 26px;
					height: 26px;
					display: grid;
					place-items: center;
					border-radius: 7px;
					background: #eff6ff;
					color: #2563eb;
				}
			}
			.v {
				margin-top: 10px;
				font-size: 26px;
				font-weight: 700;
				font-variant-numeric: tabular-nums;
				color: #0f172a;
			}
			.d {
				display: flex;
				align-items: center;
				gap: 6px;
				margin-top: 4px;
				font-size: 12px;
				color: #94a3b8;
				b {
					font-weight: 600;
					color: #475569;
				}
				// 原型 stat .up：绿色（--ok）
				.up {
					color: #10b981;
					font-weight: 600;
				}
			}
		}
	}
	// 下排两卡
	.grid2 {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 14px;
		margin-top: 14px;
		.card {
			display: flex;
			flex-direction: column;
			overflow: hidden;
			background: #fff;
			border: 1px solid #e2e8f0;
			border-radius: 14px;
			box-shadow: 0 8px 30px rgba(15, 23, 42, 0.06);
			.card-h {
				flex: none;
				display: flex;
				align-items: center;
				gap: 8px;
				padding: 14px 18px;
				border-bottom: 1px solid #e2e8f0;
				font-size: 14px;
				font-weight: 600;
				color: #0f172a;
				.card-h-ic-1 {
					color: #0284c7;
				}
				.card-h-ic-2 {
					color: #6366f1;
				}
				.more {
					margin-left: auto;
					font-size: 12.5px;
					font-weight: 400;
					color: #94a3b8;
					cursor: pointer;
					transition: color 0.15s;
					&:hover {
						color: var(--el-color-primary);
					}
				}
			}
			.card-b {
				flex: 1;
				padding: 6px 0;
				min-height: 280px;
			}
			.empty {
				display: grid;
				place-items: center;
				height: 240px;
				font-size: 13px;
				color: #94a3b8;
				a {
					color: var(--el-color-primary);
					cursor: pointer;
				}
			}
			// 生成记录行
			.gen-row {
				display: flex;
				align-items: center;
				gap: 12px;
				padding: 10px 18px;
				cursor: pointer;
				transition: background 0.15s;
				&:hover {
					background: #f8fafc;
				}
			}
			.gen-ic {
				flex: none;
				width: 34px;
				height: 34px;
				display: grid;
				place-items: center;
				border-radius: 9px;
				font-size: 11px;
				font-weight: 700;
				color: #fff;
				&.g1 {
					background: linear-gradient(135deg, #3b82f6, #6366f1);
				}
				&.g2 {
					background: linear-gradient(135deg, #0ea5e9, #2563eb);
				}
				&.g3 {
					background: linear-gradient(135deg, #10b981, #0ea5e9);
				}
			}
			.gen-main {
				flex: 1;
				min-width: 0;
				b {
					display: block;
					font-size: 13.5px;
					font-weight: 600;
					color: #0f172a;
					white-space: nowrap;
					overflow: hidden;
					text-overflow: ellipsis;
				}
				span {
					display: block;
					margin-top: 1px;
					font-size: 12px;
					color: #94a3b8;
				}
			}
			.st {
				flex: none;
				display: inline-flex;
				align-items: center;
				padding: 2px 10px;
				border-radius: 99px;
				font-size: 11.5px;
				font-weight: 500;
				.pulse {
					width: 6px;
					height: 6px;
					border-radius: 50%;
					margin-right: 5px;
					background: #2563eb;
					animation: homePulse 1.2s ease-in-out infinite;
				}
			}
			.st-done {
				background: #ecfdf5;
				color: #059669;
			}
			.st-run {
				background: #eff6ff;
				color: #2563eb;
			}
			.st-applied {
				background: #f1f5f9;
				color: #64748b;
			}
			// 文章排行表
			.rank {
				width: 100%;
				border-collapse: collapse;
				font-size: 13px;
				th {
					padding: 9px 18px;
					border-bottom: 1px solid #e2e8f0;
					background: #fafbfc;
					text-align: left;
					font-size: 12px;
					font-weight: 500;
					color: #94a3b8;
				}
				td {
					padding: 10px 18px;
					border-bottom: 1px solid #f1f5f9;
					color: #475569;
				}
				tr:last-child td {
					border-bottom: none;
				}
				.no {
					display: grid;
					place-items: center;
					width: 22px;
					height: 22px;
					border-radius: 6px;
					background: #f1f5f9;
					font-size: 11.5px;
					font-weight: 700;
					font-style: normal;
					color: #94a3b8;
				}
				tr:nth-child(1) .no {
					background: #eff6ff;
					color: #2563eb;
				}
				tr:nth-child(2) .no {
					background: #f0f9ff;
					color: #0284c7;
				}
				.tt {
					color: #0f172a;
					font-weight: 500;
					transition: color 0.15s;
					&:hover {
						color: var(--el-color-primary);
					}
				}
				.vv {
					text-align: right;
					font-variant-numeric: tabular-nums;
				}
			}
		}
	}
}
@keyframes homePulse {
	0%,
	100% {
		opacity: 0.3;
	}
	50% {
		opacity: 1;
	}
}
@media (max-width: 1200px) {
	.home-container .stats {
		grid-template-columns: repeat(2, 1fr);
	}
	.home-container .grid2 {
		grid-template-columns: 1fr;
	}
}
</style>
