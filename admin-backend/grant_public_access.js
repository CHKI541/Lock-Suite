process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const { GoogleAuth } = require('./functions/node_modules/google-auth-library');
const https = require('https');

const keyPath = "C:\\Users\\israe\\OneDrive\\Documentos\\Lock Suite segunda version\\admin-backend\\locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json";
process.env.GOOGLE_APPLICATION_CREDENTIALS = keyPath;

async function setIamPolicyForService(serviceName) {
  return new Promise(async (resolve, reject) => {
    try {
      const auth = new GoogleAuth({
        scopes: 'https://www.googleapis.com/auth/cloud-platform'
      });
      const client = await auth.getClient();
      const tokenResponse = await client.getAccessToken();
      const accessToken = tokenResponse.token;
      
      console.log(`Token obtained for ${serviceName}.`);

      const projectId = "locksuite-nueva";
      const region = "us-central1";

      const body = JSON.stringify({
        policy: {
          bindings: [
            {
              role: "roles/run.invoker",
              members: [
                "allUsers"
              ]
            }
          ]
        }
      });

      const options = {
        hostname: 'run.googleapis.com',
        path: `/v2/projects/${projectId}/locations/${region}/services/${serviceName}:setIamPolicy`,
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${accessToken}`,
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body)
        }
      };

      const req = https.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => {
          data += chunk;
        });
        res.on('end', () => {
          console.log(`Service: ${serviceName} -> Status Code: ${res.statusCode}`);
          console.log('Response body:', data);
          if (res.statusCode === 200) {
            console.log(`SUCCESS: Public access granted to ${serviceName}!`);
            resolve();
          } else {
            console.log(`FAILURE: Could not grant public access to ${serviceName}.`);
            reject(new Error(`Failed with status ${res.statusCode}`));
          }
        });
      });

      req.on('error', (e) => {
        console.error('Request error:', e);
        reject(e);
      });

      req.write(body);
      req.end();

    } catch (error) {
      console.error(`Error in setIamPolicyForService for ${serviceName}:`, error);
      reject(error);
    }
  });
}

async function run() {
  try {
    await setIamPolicyForService("sendcommandv5");
    await setIamPolicyForService("sendcommandv6");
    console.log("All done!");
  } catch (err) {
    console.error("Run failed:", err);
  }
}

run();
