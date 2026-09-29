// Hand-rolled MCP (JSON-RPC 2.0 over Streamable HTTP) request handler.
// No @modelcontextprotocol/sdk dependency: plain JSON-RPC like the KSHT
// reference (initialize / server/discover / ping / tools/list / tools/call).

import { hasValidApiKey } from "./auth.ts";
import type { DeviceGateway, EmuseEnv } from "./protocol.ts";
import { TOOL_DEFS, toolByName } from "./tools.ts";

const PROTOCOL = "2026-07-28";
const SUPPORTED_PROTOCOL_VERSIONS = [
  "2026-07-28",
  "2025-11-25",
  "2025-06-18",
] as const;
const MAX_BODY_BYTES = 1024 * 1024;

interface JsonRpcMessage {
  jsonrpc?: string;
  id?: unknown;
  method?: string;
  params?: { name?: unknown; arguments?: unknown; protocolVersion?: unknown };
}

const negotiateProtocol = (requested: unknown): string =>
  typeof requested === "string" &&
  (SUPPORTED_PROTOCOL_VERSIONS as readonly string[]).includes(requested)
    ? requested
    : PROTOCOL;

const json = (
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });

const rpcError = (id: unknown, code: number, message: string, data?: unknown) =>
  json({ jsonrpc: "2.0", id, error: { code, message, data } });

const rpcResult = (id: unknown, result: Record<string, unknown>) =>
  json({
    jsonrpc: "2.0",
    id,
    result: {
      ttlMs: 0,
      cacheScope: "private",
      ...result,
      resultType: "complete",
    },
  });

const toolOk = (id: unknown, result: unknown) => {
  // MCP spec: structuredContent must be an object. Wrap arrays/primitives so
  // strict clients (pydantic validation) don't reject the response.
  const structured =
    result !== null && typeof result === "object" && !Array.isArray(result)
      ? result
      : { value: result };
  return rpcResult(id, {
    content: [{ type: "text", text: JSON.stringify(result) }],
    structuredContent: structured,
  });
};

const toolFailed = (id: unknown, message: string) =>
  rpcResult(id, {
    isError: true,
    content: [{ type: "text", text: message }],
    structuredContent: { code: "DEVICE_ERROR", message, retryable: false },
  });

// tool_flags: read or mutate the per-tool on/off flags stored in the DO.
const handleToolFlags = async (
  args: Record<string, unknown>,
  gateway: DeviceGateway,
): Promise<unknown> => {
  if (args.reset === true) {
    const enableAll: Record<string, boolean> = {};
    for (const tool of TOOL_DEFS) {
      if (tool.name !== "tool_flags") enableAll[tool.name] = true;
    }
    await gateway.setToolFlags(enableAll);
  } else if (args.set !== null && typeof args.set === "object") {
    const updates: Record<string, boolean> = {};
    for (const [name, enabled] of Object.entries(
      args.set as Record<string, unknown>,
    )) {
      if (typeof enabled === "boolean") updates[name] = enabled;
    }
    await gateway.setToolFlags(updates);
  }
  const flags = await gateway.getToolFlags();
  return {
    tools: TOOL_DEFS.filter((tool) => tool.name !== "tool_flags").map(
      (tool) => ({
        name: tool.name,
        enabled: flags[tool.name] !== false,
        kind: tool.kind,
      }),
    ),
  };
};

export const handleMcpRequest = async (
  request: Request,
  env: EmuseEnv,
  gateway: DeviceGateway,
): Promise<Response> => {
  const url = new URL(request.url);
  if (env.EMUSE_CHANNEL_DISABLED === "1") {
    return json({ error: "MCP channel disabled" }, 503);
  }
  if (url.pathname !== "/mcp") return json({ error: "Not Found" }, 404);
  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return json({ error: "request_too_large" }, 413);
  }
  let message: JsonRpcMessage;
  try {
    message = (await request.json()) as JsonRpcMessage;
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  if (!(await hasValidApiKey(request, env))) {
    return new Response(
      JSON.stringify({
        jsonrpc: "2.0",
        id: message.id ?? null,
        error: { code: -32000, message: "invalid_token" },
      }),
      {
        status: 401,
        headers: {
          "content-type": "application/json",
          "www-authenticate": 'Bearer realm="mcp"',
        },
      },
    );
  }

  const method = message.method ?? "";
  if (method === "initialize") {
    return rpcResult(message.id, {
      protocolVersion: negotiateProtocol(message.params?.protocolVersion),
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "e-muse-mcp", version: "1.0.0" },
    });
  }
  if (method === "server/discover") {
    return rpcResult(message.id, {
      supportedVersions: [...SUPPORTED_PROTOCOL_VERSIONS],
      capabilities: { tools: { listChanged: false } },
      _meta: {
        "io.modelcontextprotocol/serverInfo": {
          name: "e-muse-mcp",
          version: "1.0.0",
        },
      },
    });
  }
  if (method === "ping") return rpcResult(message.id, {});
  if (method === "notifications/initialized") {
    return new Response(null, { status: 202 });
  }
  if (method === "tools/list") {
    if (env.EMUSE_READ_DISABLED === "1") {
      return rpcError(message.id, -32000, "MCP reads disabled");
    }
    const flags = await gateway.getToolFlags();
    return rpcResult(message.id, {
      tools: TOOL_DEFS.filter(
        (tool) => tool.name === "tool_flags" || flags[tool.name] !== false,
      ).map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema,
        annotations: tool.annotations,
      })),
    });
  }
  if (method === "tools/call") {
    const name = String(message.params?.name ?? "");
    const def = toolByName.get(name);
    if (!def) return rpcError(message.id, -32601, `Unknown tool ${name}`);
    const args = (message.params?.arguments ?? {}) as Record<string, unknown>;
    if (name === "tool_flags") {
      return toolOk(message.id, await handleToolFlags(args, gateway));
    }
    const flags = await gateway.getToolFlags();
    if (flags[name] === false) {
      return rpcError(
        message.id,
        -32000,
        `Tool "${name}" is disabled. Call tool_flags to see or change the enabled tools.`,
      );
    }
    if (def.kind === "write" && env.EMUSE_WRITE_DISABLED === "1") {
      return rpcError(message.id, -32000, "MCP writes disabled");
    }
    try {
      if (name === "device_list") {
        return toolOk(message.id, await gateway.listDevices());
      }
      if (!def.cmd) return rpcError(message.id, -32601, `Unknown tool ${name}`);
      const { deviceId, ...cmdArgs } = args;
      const timeoutMs =
        typeof cmdArgs.timeoutMs === "number" ? cmdArgs.timeoutMs : 30000;
      const result = await gateway.dispatch(
        typeof deviceId === "string" ? deviceId : undefined,
        def.cmd,
        cmdArgs,
        timeoutMs,
      );
      if (name === "screen_capture" && result && typeof result === "object") {
        const shot = result as {
          pngBase64?: string;
          width?: number;
          height?: number;
        };
        return rpcResult(message.id, {
          content: [
            {
              type: "image",
              data: shot.pngBase64 ?? "",
              mimeType: "image/png",
            },
            {
              type: "text",
              text: JSON.stringify({ width: shot.width, height: shot.height }),
            },
          ],
          structuredContent: { width: shot.width, height: shot.height },
        });
      }
      return toolOk(message.id, result);
    } catch (error) {
      return toolFailed(
        message.id,
        error instanceof Error ? error.message : "Internal error",
      );
    }
  }
  return rpcError(message.id, -32601, `Unknown method ${method}`);
};
