// E-muse Worker entrypoint: health check, device WebSocket upgrades and
// the authenticated /mcp endpoint, all served through the DeviceLink
// Durable Object (single "fleet" instance).

import { hasValidApiKey } from "./auth.ts";
import { DeviceLink } from "./device.ts";
import type { EmuseEnv } from "./protocol.ts";

export { DeviceLink };

const MAX_BODY_BYTES = 1024 * 1024;

// Auth gate for the public /mcp surface (same shape as KSHT's publicMcp).
const rejectMcpRequest = async (
  request: Request,
  env: EmuseEnv,
): Promise<Response | null> => {
  const url = new URL(request.url);
  if (env.EMUSE_CHANNEL_DISABLED === "1") {
    return Response.json({ error: "MCP channel disabled" }, { status: 503 });
  }
  if (url.pathname !== "/mcp") {
    return Response.json({ error: "Not Found" }, { status: 404 });
  }
  if (request.method !== "POST") {
    return Response.json({ error: "Method not allowed" }, { status: 405 });
  }
  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return Response.json({ error: "request_too_large" }, { status: 413 });
  }
  if (!(await hasValidApiKey(request, env))) {
    return Response.json(
      { error: "invalid_token" },
      {
        status: 401,
        headers: { "www-authenticate": 'Bearer realm="mcp"' },
      },
    );
  }
  return null;
};

export default {
  async fetch(request: Request, env: EmuseEnv): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return Response.json({ ok: true, service: "e-muse-mcp" });
    }
    const stub = env.DEVICE_LINK.get(env.DEVICE_LINK.idFromName("fleet"));
    if (url.pathname === "/device/connect") {
      return stub.fetch(request);
    }
    const rejected = await rejectMcpRequest(request, env);
    if (rejected) return rejected;
    return stub.fetch(request);
  },
};
