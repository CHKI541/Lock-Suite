// Script para obtener URLs de Cloud Run y reparar IAM de las funciones Gen 1
const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const sa = JSON.parse(fs.readFileSync('./locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json', 'utf8'));

function b64url(str) {
  return Buffer.from(str).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'');
}

function makeJWT() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({alg:'RS256',typ:'JWT'}));
  const payload = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/cloud-platform',
    aud: 'https://oauth2.googleapis.com/token',
    exp: now + 3600,
    iat: now
  }));
  const sign_input = `${header}.${payload}`;
  const sign = crypto.createSign('RSA-SHA256');
  sign.update(sign_input);
  const sig = sign.sign(sa.private_key, 'base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'');
  return `${sign_input}.${sig}`;
}

function post(options, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => resolve({status: res.statusCode, body: data}));
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function get(url, token) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.get({hostname: u.hostname, path: u.pathname + u.search, headers: {Authorization: `Bearer ${token}`}}, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => resolve({status: res.statusCode, body: data}));
    }).on('error', reject);
  });
}

async function main() {
  // 1. Obtener token OAuth2
  const jwt = makeJWT();
  const body = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`;
  const tokenRes = await post({
    hostname: 'oauth2.googleapis.com',
    path: '/token',
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'}
  }, body);
  const token = JSON.parse(tokenRes.body).access_token;
  if (!token) { console.error('No se pudo obtener token:', tokenRes.body); return; }
  console.log('✅ Token obtenido');

  // 2. Listar servicios de Cloud Run para encontrar URLs Gen 2
  const runRes = await get(
    `https://run.googleapis.com/v2/projects/locksuite-nueva/locations/us-central1/services`,
    token
  );
  const runData = JSON.parse(runRes.body);
  console.log('\n=== ALL Cloud Run Services ===');
  if (runData.services) {
    runData.services.forEach(s => {
      const name = s.name.split('/').pop();
      console.log(`${name}: ${s.urls ? s.urls[0] : 'sin URL'}`);
    });
  } else {
    console.log('Sin servicios o error:', JSON.stringify(runData).substring(0, 300));
  }

  // Setear IAM para permitir acceso público a sendcommandv3 y sendcommandv4
  const CR_SERVICES = ['sendcommandv3', 'sendcommandv4'];
  for (const svc of CR_SERVICES) {
    console.log(`\n=== Seteando IAM en Cloud Run: ${svc} ===`);
    const iamBody = JSON.stringify({
      policy: {
        bindings: [{ role: 'roles/run.invoker', members: ['allUsers'] }]
      }
    });
    const iamRes = await post({
      hostname: 'run.googleapis.com',
      path: `/v2/projects/locksuite-nueva/locations/us-central1/services/${svc}:setIamPolicy`,
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(iamBody)
      }
    }, iamBody);
    console.log(`${svc} IAM: ${iamRes.status} — ${iamRes.body.substring(0, 150)}`);
  }
}

main().catch(console.error);



