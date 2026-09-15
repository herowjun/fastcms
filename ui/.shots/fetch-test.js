const http = require('http');
const zlib = require('zlib');

const paths = [
  '/assets/js/@vue-6dabbe94.js',
  '/assets/css/fastcms-c8842880.css',
  '/assets/js/@element-plus-64e6e14a.js',
];

const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });

function fetchOnce(p, i) {
  return new Promise((resolve) => {
    const req = http.request({
      host: 'localhost', port: 8080, path: p, agent,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
        'Accept': '*/*',
        'Accept-Encoding': 'gzip, deflate, br, zstd',
        'Connection': 'keep-alive',
      }
    }, (res) => {
      const chunks = [];
      let bytes = 0;
      res.on('data', c => { chunks.push(c); bytes += c.length; });
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        const h = res.headers;
        console.log(`#${i} ${p}`);
        console.log(`  status=${res.statusCode} content-length-header=${h['content-length']} actual-bytes=${bytes} content-encoding=${h['content-encoding'] || 'none'} transfer-encoding=${h['transfer-encoding'] || 'none'}`);
        console.log(`  first-bytes=${buf.subarray(0, 4).toString('hex')} (1f8b=gzip magic)`);
        resolve();
      });
      res.on('error', e => { console.log(`#${i} ${p} RES_ERR ${e.message}`); resolve(); });
    });
    req.on('error', e => { console.log(`#${i} ${p} REQ_ERR ${e.message}`); resolve(); });
    req.end();
  });
}

(async () => {
  for (let i = 0; i < paths.length; i++) await fetchOnce(paths[i], i);
  // 重复请求第二轮，模拟 keep-alive 复用
  for (let i = 0; i < paths.length; i++) await fetchOnce(paths[i], 'r' + i);
  agent.destroy();
})();
