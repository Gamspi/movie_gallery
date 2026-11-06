// Cloudflare Worker: allowlisted reverse proxy for VideoGallery.
// URL scheme: https://<worker>/<host>/<path>?<query> -> https://<host>/<path>?<query>

const ALLOWED = [
  "api.themoviedb.org",
  "image.tmdb.org",
  "rutor.info",
  "rutracker.org",
];
const METHODS = ["GET", "HEAD", "POST"];

// Exact match or subdomain of an allowed host.
function isAllowed(host) {
  return ALLOWED.some((h) => host === h || host.endsWith("." + h));
}

// Valid hostname: lowercase labels, no port, userinfo or empty parts.
function validHost(host) {
  return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(host);
}

// Constant-time comparison of SHA-256 digests.
async function keyMatches(given, expected) {
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  if (typeof crypto.subtle.timingSafeEqual === "function") {
    return crypto.subtle.timingSafeEqual(a, b);
  }
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

export default {
  async fetch(request, env) {
    if (!env.PROXY_KEY) return new Response("PROXY_KEY not configured", { status: 500 });
    if (!METHODS.includes(request.method)) return new Response("Method not allowed", { status: 405 });

    const given = request.headers.get("x-vg-key") || "";
    if (!(await keyMatches(given, env.PROXY_KEY))) return new Response("Forbidden", { status: 403 });

    const url = new URL(request.url);
    if (url.pathname === "/" ) return new Response("ok", { status: 200 });

    // First path segment is the upstream host, the rest is its path.
    const rest = url.pathname.slice(1);
    const slash = rest.indexOf("/");
    const host = (slash === -1 ? rest : rest.slice(0, slash)).toLowerCase();
    const path = slash === -1 ? "/" : rest.slice(slash);
    if (!validHost(host) || !isAllowed(host)) return new Response("Forbidden host", { status: 403 });

    // Drop proxy-specific and client-identifying headers.
    const headers = new Headers();
    for (const [k, v] of request.headers) {
      const n = k.toLowerCase();
      if (n === "host" || n === "x-vg-key" || n === "x-real-ip" ||
          n.startsWith("cf-") || n.startsWith("x-forwarded-")) continue;
      headers.set(k, v);
    }

    const init = { method: request.method, headers, redirect: "manual" };
    if (request.method === "POST") init.body = request.body;

    return fetch(new Request("https://" + host + path + url.search, init));
  },
};
