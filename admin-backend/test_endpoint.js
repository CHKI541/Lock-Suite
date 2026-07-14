const https = require('https');

const options = {
  hostname: 'sendcommandv4-687828714595.us-central1.run.app',
  port: 443,
  path: '/',
  method: 'OPTIONS',
  headers: {
    'Origin': 'https://locksuite-nueva.web.app',
    'Access-Control-Request-Method': 'POST',
    'Access-Control-Request-Headers': 'content-type,authorization'
  }
};

const req = https.request(options, (res) => {
  console.log(`STATUS: ${res.statusCode}`);
  console.log('HEADERS:', JSON.stringify(res.headers, null, 2));
});

req.on('error', (e) => {
  console.error(`Error: ${e.message}`);
});

req.end();
