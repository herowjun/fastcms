const puppeteer = require('puppeteer-core');
const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJhdXRoIjoiMSIsInVzZXJJZCI6MSwidXNlcm5hbWUiOiJhZG1pbiIsImV4cCI6MTc4OTU0MDg5N30.BgoRTZHQNgNUP_7ovvZouaW_eqCjH8TKZJFUj5vz9yE';
const BASE = 'http://localhost:8080/fastcms.html';
const CHROME = 'C:\\Users\\PC1\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
	const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'], defaultViewport: { width: 1920, height: 1080 } });
	const page = await browser.newPage();
	await page.goto(BASE, { waitUntil: 'networkidle2', timeout: 30000 });
	await page.evaluate((t) => {
		localStorage.setItem('Fastcms:token', JSON.stringify(t));
		localStorage.setItem('Fastcms:userInfo', JSON.stringify({ username: 'admin', photo: '/header.jpg', time: Date.now(), hasRole: true, version: '2.0.0', userType: 1 }));
	}, TOKEN);
	await page.reload({ waitUntil: 'networkidle2', timeout: 30000 });
	await sleep(2000);
	await page.goto(BASE + '#/template/edit', { waitUntil: 'networkidle2', timeout: 30000 });
	await page.waitForSelector('.toolbar', { timeout: 20000 });
	await sleep(1500);

	// 逐个选择模板，检查树卡片内部布局链路
	await page.click('.toolbar .el-select');
	await page.waitForSelector('.el-select-dropdown__item', { timeout: 8000 });
	await sleep(400);
	const options = await page.$$eval('.el-select-dropdown__item', (els) => els.map((e) => e.textContent.trim()));
	console.log('TEMPLATES=', JSON.stringify(options));

	for (let idx = 0; idx < Math.min(options.length, 5); idx++) {
		// 重新打开下拉选择第 idx 个（用 DOM click 绕过可点击性检查）
		await page.click('.toolbar .el-select');
		await sleep(500);
		await page.evaluate((i) => {
			const items = document.querySelectorAll('.el-select-dropdown__item');
			if (items[i]) items[i].click();
		}, idx);
		await sleep(2200);

		const m = await page.evaluate(() => {
			const card = document.querySelector('.tree-card');
			const body = document.querySelector('.tree-card .el-card__body');
			const treeBody = document.querySelector('.tree-card .tree-body');
			const tree = document.querySelector('.tree-card .el-tree');
			const r = (el) => el ? Math.round(el.getBoundingClientRect().height) : null;
			return {
				tpl: document.querySelector('.toolbar .el-select .el-select__selected-item')?.textContent?.trim() || document.querySelector('.toolbar .el-select input')?.value || '?',
				cardH: r(card),
				bodyDisplay: body ? getComputedStyle(body).display : null,
				bodyOverflow: body ? getComputedStyle(body).overflow : null,
				bodyH: r(body),
				treeBodyDisplay: treeBody ? getComputedStyle(treeBody).display : null,
				treeBodyH: r(treeBody),
				treeH: r(tree),
				treeScrollH: tree ? tree.scrollHeight : null,
				treeClientH: tree ? tree.clientHeight : null,
				treeOverflow: tree ? getComputedStyle(tree).overflow : null,
				nodeCount: document.querySelectorAll('.tree-card .el-tree-node__content').length,
				// 树是否溢出卡片（无滚动时的直接证据）
				treeBottom: tree ? Math.round(tree.getBoundingClientRect().bottom) : null,
				cardBottom: card ? Math.round(card.getBoundingClientRect().bottom) : null,
			};
		});
		console.log('TPL' + idx + '=', JSON.stringify(m));
	}
	await browser.close();
})().catch((e) => { console.error('FATAL:', e.message); process.exit(1); });
