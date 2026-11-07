// `npm run login`: one-time browser sign-in. Saves a refresh token to .secrets/token.json.

import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import {
  SCOPES,
  SyncError,
  TOKEN_FILE,
  authUrl,
  exchangeCode,
  loadClient,
  oauthError,
  pkce,
  saveToken,
} from './auth.js';

const TIMEOUT_MS = 5 * 60 * 1000;

function openBrowser(url) {
  const [command, args] =
    process.platform === 'win32' ? ['explorer.exe', [url]]
    : process.platform === 'darwin' ? ['open', [url]]
    : ['xdg-open', [url]];
  try {
    spawn(command, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref();
  } catch {
    // The URL is printed as well.
  }
}

function page(title, text) {
  return `<!doctype html><meta charset="utf-8"><title>${title}</title>
<body style="font-family:system-ui,sans-serif;background:#f6f1e7;color:#1f2a24;max-width:36rem;margin:4rem auto;padding:0 1rem">
<h1 style="font-size:1.25rem">${title}</h1><p>${text}</p></body>`;
}

async function main() {
  const client = loadClient();
  const { verifier, challenge } = pkce();
  const state = randomBytes(16).toString('hex');

  const result = await new Promise((resolve, reject) => {
    const server = createServer((req, res) => {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.pathname !== '/') {
        res.writeHead(404).end();
        return;
      }
      const finish = (outcome, status, title, text) => {
        clearTimeout(timer);
        // Connection: close so the browser doesn't hold a keep-alive socket open; once the page
        // is sent, stop the server and drop any other sockets so nothing keeps the process alive.
        res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', Connection: 'close' });
        res.end(page(title, text), () => {
          server.close();
          server.closeAllConnections();
        });
        outcome();
      };
      const error = url.searchParams.get('error');
      if (error) {
        const failure = oauthError(error, url.searchParams.get('error_description') ?? '');
        finish(() => reject(failure), 400, 'Sign-in failed', 'Go back to the terminal for details.');
      } else if (url.searchParams.get('state') !== state) {
        finish(() => reject(new SyncError('Sign-in reply did not match this login. Run `npm run login` again.')),
          400, 'Sign-in failed', 'Go back to the terminal and try again.');
      } else {
        const redirectUri = `http://127.0.0.1:${server.address().port}`;
        finish(() => resolve({ code: url.searchParams.get('code'), redirectUri }),
          200, 'Signed in', 'You can close this tab and go back to the terminal.');
      }
    });

    const timer = setTimeout(() => {
      server.close();
      server.closeAllConnections();
      reject(new SyncError(
        `No reply from Google after 5 minutes.
If the browser showed "Access blocked" or "Error 400: admin_policy_enforced", NITH's Google
Workspace does not allow this app to read Classroom. Ask the Workspace admin to trust your
OAuth client ID, or sign in with another account that is in the class.`,
      ));
    }, TIMEOUT_MS);

    server.listen(0, '127.0.0.1', () => {
      const redirectUri = `http://127.0.0.1:${server.address().port}`;
      const url = authUrl({ clientId: client.client_id, redirectUri, state, challenge });
      console.log('Opening the Google sign-in page. Sign in with the NITH account that is in the class.');
      console.log('If the browser does not open, copy this link into it:\n');
      console.log(url + '\n');
      console.log('Google will warn that the app is unverified. That is expected for your own app:');
      console.log('choose "Advanced", then "Go to <app name> (unsafe)", then allow the read-only permissions.\n');
      openBrowser(url);
    });
  });

  const token = await exchangeCode({ client, code: result.code, redirectUri: result.redirectUri, verifier });
  if (!token.refresh_token) {
    throw new SyncError('Google did not return a refresh token. Remove the app at myaccount.google.com/permissions and log in again.');
  }
  const granted = new Set(String(token.scope ?? '').split(' '));
  const missing = SCOPES.filter((s) => !granted.has(s));
  if (missing.length) {
    throw new SyncError(`Some permissions were not granted:\n  ${missing.join('\n  ')}\nRun \`npm run login\` again and tick every box.`);
  }

  saveToken({ refresh_token: token.refresh_token, scope: token.scope, savedAt: new Date().toISOString() });
  console.log(`Signed in. Refresh token saved to ${TOKEN_FILE}`);
  console.log('Next: npm run dry-run');
}

main().catch((error) => {
  console.error(`\n${error instanceof SyncError ? error.message : error.stack ?? error}`);
  process.exitCode = 1; // not process.exit(): let open handles close first (libuv asserts on Windows otherwise)
});
