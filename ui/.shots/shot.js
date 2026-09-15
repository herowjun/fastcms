const puppeteer = require('D:/Program Files/nodejs/node_global/node_modules/@modelcontextprotocol/server-puppeteer/node_modules/puppeteer');
const fs = require('fs');
const path = require('path');

const DIR = __dirname;

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function waitFile(f, timeoutMs) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const iv = setInterval(() => {
      if (fs.existsSync(f) && fs.statSync(f).size > 0) { clearInterval(iv); resolve(fs.readFileSync(f, 'utf8').trim()); }
      else if (Date.now() - start > timeoutMs) { clearInterval(iv); reject(new Error('timeout waiting ' + f)); }
    }, 400);
  });
}

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    defaultViewport: { width: 1440, height: 900 },
    args: ['--no-sandbox', '--disable-gpu', '--force-device-scale-factor=1', '--no-proxy-server', '--proxy-bypass-list=*']
  });
  const page = await browser.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') console.log('[console.' + m.type() + ']', m.text().substring(0, 300)); });
  page.on('requestfailed', r => console.log('[reqfail]', r.url().substring(0, 150), r.failure() && r.failure().errorText));
  page.on('response', r => { if (r.status() >= 400) console.log('[http' + r.status() + ']', r.url().substring(0, 150)); });

  // ===== 1. 原型截图 =====
  await page.goto('file:///D:/Desktop/fastcms-ui-prototype.html', { waitUntil: 'load' });
  await sleep(800);
  const protoLogin = await page.$('.login');
  if (protoLogin) await protoLogin.screenshot({ path: path.join(DIR, 'proto-login.png') });
  const protoDash = await page.$('.dash');
  if (protoDash) await protoDash.screenshot({ path: path.join(DIR, 'proto-dash.png') });
  console.log('STEP1_DONE proto screenshots');

  // ===== 2. 系统登录页 =====
  await page.goto('http://localhost:8080/fastcms.html#/login', { waitUntil: 'networkidle2', timeout: 30000 });
  try {
    await page.waitForSelector('.login-panel', { timeout: 20000 });
  } catch (e) {
    console.log('LOGIN_PANEL_NOT_FOUND url=', page.url(), 'title=', await page.title());
    await page.screenshot({ path: path.join(DIR, 'debug-load.png') });
    const html = await page.content();
    fs.writeFileSync(path.join(DIR, 'debug-load.html'), html.substring(0, 3000));
    throw e;
  }
  await sleep(2000);
  const sysLogin = await page.$('.login-panel');
  await sysLogin.screenshot({ path: path.join(DIR, 'system-login.png') });
  await page.screenshot({ path: path.join(DIR, 'system-login-vp.png') });
  console.log('STEP2_DONE system login screenshot');

  // ===== 3. 验证码 =====
  const capImg = await page.$('.login-content-code-img');
  if (!capImg) { console.log('NO_CAPTCHA_FIELD'); await browser.close(); return; }
  await capImg.screenshot({ path: path.join(DIR, 'captcha.png') });
  console.log('STEP3_DONE captcha saved, waiting for captcha.txt ...');
  const code = await waitFile(path.join(DIR, 'captcha.txt'), 180000);
  console.log('captcha got:', code);

  // ===== 4. 填表登录 =====
  const inputs = await page.$$('.login-content-form input');
  await inputs[0].type('admin', { delay: 30 });
  await inputs[1].type('1', { delay: 30 });
  await inputs[2].type(code, { delay: 30 });
  await sleep(300);
  await page.click('.login-content-submit');
  console.log('STEP4_DONE submitted, waiting home ...');

  // ===== 5. 首页 =====
  try {
    await page.waitForSelector('.home-container', { timeout: 20000 });
  } catch (e) {
    console.log('HOME_TIMEOUT, screenshot current state');
    await page.screenshot({ path: path.join(DIR, 'home-fail.png') });
    await browser.close();
    return;
  }
  await sleep(2500); // 等数字动画/数据加载
  await page.screenshot({ path: path.join(DIR, 'system-home.png') });
  console.log('STEP5_DONE home screenshot');

  await browser.close();
  console.log('ALL_DONE');
})().catch(e => { console.error('FATAL', e); process.exit(1); });
