// Downloads the notice pages: one request per page, browser-like User-Agent naming the app.

import { PageError, parsePage } from './parse.js';

/**
 * @returns `{ page, ok: true, rows }` or `{ page, ok: false, error }` per page; never throws.
 */
export async function fetchPages(config, fetchImpl = fetch) {
  const results = [];
  for (const page of config.pages) {
    try {
      const response = await fetchImpl(page.url, {
        headers: { 'User-Agent': config.userAgent, Accept: 'text/html' },
        signal: AbortSignal.timeout(config.timeoutMs),
        redirect: 'follow',
      });
      if (!response.ok) throw new PageError(`HTTP ${response.status}`);
      const html = await response.text();
      results.push({ page, ok: true, rows: parsePage(html, page.url) });
    } catch (error) {
      const reason = error?.name === 'TimeoutError' ? `no answer within ${config.timeoutMs / 1000} s` : error.message;
      results.push({ page, ok: false, error: reason });
    }
  }
  return results;
}
