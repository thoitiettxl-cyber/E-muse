// DeviceLink Durable Object: holds WebSocket connections from E-Muse
// Android apps, authenticates them with the hello handshake, and routes
// MCP tool calls to devices as correlated {id, cmd, args} commands.

import { DurableObject } from "cloudflare:workers";
import { secretValuesEqual } from "./auth.ts";
import type {
  Cmd,
  DeviceCommand,
  DeviceGateway,
  DeviceInfo,
  EmuseEnv,
  HelloMessage,
} from "./protocol.ts";
import { TOOL_DEFS, toolByName } from "./tools.ts";
import { handleMcpRequest } from "./server.ts";

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
  deviceId: string;
}

interface ConnMeta {
  deviceId: string;
  deviceName: string;
  connectedAt: string;
  authed: boolean;
}

const MAX_TIMEOUT_MS = 120_000;
const AUTH_TIMEOUT_MS = 15_000;

export class DeviceLink extends DurableObject<EmuseEnv> {
  private conns = new Map<WebSocket, ConnMeta>();
  private byDevice = new Map<string, WebSocket>();
  private pending = new Map<string, PendingCall>();

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/device/connect") {
      return this.handleDeviceConnect(request);
    }
    if (url.pathname === "/mcp") {
      return handleMcpRequest(request, this.env, this.gateway());
    }
    return Response.json({ error: "Not Found" }, { status: 404 });
  }

  private handleDeviceConnect(request: Request): Response {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server);
    this.conns.set(server, {
      deviceId: "",
      deviceName: "",
      connectedAt: new Date().toISOString(),
      authed: false,
    });
    // Best-effort: drop sockets that never complete the hello handshake.
    setTimeout(() => {
      const meta = this.conns.get(server);
      if (meta && !meta.authed) {
        try {
          server.close(4401, "auth timeout");
        } catch {
          // ignore
        }
        this.dropConn(server);
      }
    }, AUTH_TIMEOUT_MS);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(
    ws: WebSocket,
    message: string | ArrayBuffer,
  ): Promise<void> {
    const meta = this.conns.get(ws);
    if (!meta) return;
    let msg: Record<string, unknown>;
    try {
      msg =
        typeof message === "string"
          ? (JSON.parse(message) as Record<string, unknown>)
          : (JSON.parse(new TextDecoder().decode(message)) as Record<
              string,
              unknown
            >);
    } catch {
      return;
    }
    if (!meta.authed) {
      if (msg.type === "hello") {
        await this.handleHello(ws, meta, msg as unknown as HelloMessage);
      } else {
        try {
          ws.close(4401, "unauthorized");
        } catch {
          // ignore
        }
        this.dropConn(ws);
      }
      return;
    }
    if (msg.type === "result" && typeof msg.id === "string") {
      const call = this.pending.get(msg.id);
      if (!call) return;
      this.pending.delete(msg.id);
      clearTimeout(call.timer);
      if (msg.ok) call.resolve(msg.result);
      else
        call.reject(
          new Error(
            typeof msg.error === "string" ? msg.error : "device error",
          ),
        );
    }
  }

  private async handleHello(
    ws: WebSocket,
    meta: ConnMeta,
    hello: HelloMessage,
  ): Promise<void> {
    const expected = (this.env.EMUSE_API_KEY ?? "").trim();
    const presented = (hello.token ?? "").trim();
    const ok =
      typeof hello.deviceId === "string" &&
      hello.deviceId.length > 0 &&
      (await secretValuesEqual(presented, expected));
    if (!ok) {
      try {
        ws.close(4401, "unauthorized");
      } catch {
        // ignore
      }
      this.dropConn(ws);
      return;
    }
    const old = this.byDevice.get(hello.deviceId);
    if (old && old !== ws) {
      try {
        old.close(4400, "replaced by new connection");
      } catch {
        // ignore
      }
      this.dropConn(old);
    }
    meta.authed = true;
    meta.deviceId = hello.deviceId;
    meta.deviceName = String(hello.deviceName ?? "android");
    this.byDevice.set(hello.deviceId, ws);
    ws.send(JSON.stringify({ type: "hello_ack", ok: true }));
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.dropConn(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.dropConn(ws);
  }

  private dropConn(ws: WebSocket): void {
    const meta = this.conns.get(ws);
    this.conns.delete(ws);
    if (meta && meta.authed && meta.deviceId) {
      if (this.byDevice.get(meta.deviceId) === ws) {
        this.byDevice.delete(meta.deviceId);
      }
      for (const [id, call] of this.pending) {
        if (call.deviceId === meta.deviceId) {
          this.pending.delete(id);
          clearTimeout(call.timer);
          call.reject(new Error("device disconnected"));
        }
      }
    }
  }

  private gateway(): DeviceGateway {
    const self = this;
    return {
      listDevices: async (): Promise<DeviceInfo[]> =>
        [...this.byDevice.entries()].map(([deviceId, ws]) => {
          const meta = this.conns.get(ws);
          return {
            deviceId,
            deviceName: meta?.deviceName ?? "android",
            connectedAt: meta?.connectedAt ?? new Date(0).toISOString(),
          };
        }),
      dispatch: (
        deviceId: string | undefined,
        cmd: Cmd,
        args: Record<string, unknown>,
        timeoutMs = 30000,
      ): Promise<unknown> => this.rpc(deviceId, cmd, args, timeoutMs),
      getToolFlags: async (): Promise<Record<string, boolean>> => {
        const disabled = await self.getDisabledTools();
        const flags: Record<string, boolean> = {};
        for (const tool of TOOL_DEFS) {
          if (tool.name === "tool_flags") continue;
          flags[tool.name] = !disabled.has(tool.name);
        }
        return flags;
      },
      setToolFlags: async (
        updates: Record<string, boolean>,
      ): Promise<Record<string, boolean>> => {
        const disabled = await self.getDisabledTools();
        for (const [name, enabled] of Object.entries(updates)) {
          // tool_flags can never be disabled; unknown names are ignored.
          if (name === "tool_flags" || !toolByName.has(name)) continue;
          if (enabled) disabled.delete(name);
          else disabled.add(name);
        }
        await self.setDisabledTools(disabled);
        return self.gateway().getToolFlags();
      },
    };
  }

  private async getDisabledTools(): Promise<Set<string>> {
    const stored = await this.ctx.storage.get<string[]>("disabledTools");
    return new Set(stored ?? []);
  }

  private async setDisabledTools(disabled: Set<string>): Promise<void> {
    await this.ctx.storage.put("disabledTools", [...disabled]);
  }

  private pickSocket(deviceId: string | undefined): WebSocket {
    if (deviceId) {
      const ws = this.byDevice.get(deviceId);
      if (!ws) throw new Error(`device not connected: ${deviceId}`);
      return ws;
    }
    const ids = [...this.byDevice.keys()];
    if (ids.length === 0) throw new Error("no device connected");
    if (ids.length > 1) {
      throw new Error(
        `multiple devices connected (${ids.length}); specify deviceId`,
      );
    }
    const ws = this.byDevice.get(ids[0]);
    if (!ws) throw new Error("no device connected");
    return ws;
  }

  private rpc(
    deviceId: string | undefined,
    cmd: Cmd,
    args: Record<string, unknown>,
    timeoutMs: number,
  ): Promise<unknown> {
    const ws = this.pickSocket(deviceId);
    const resolvedId =
      deviceId ?? ([...this.byDevice.keys()][0] as string | undefined) ?? "";
    const id = crypto.randomUUID();
    const payload: DeviceCommand = { id, cmd, args };
    const bounded = Math.min(Math.max(timeoutMs, 1000), MAX_TIMEOUT_MS);
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`device timeout after ${bounded}ms`));
      }, bounded);
      this.pending.set(id, { resolve, reject, timer, deviceId: resolvedId });
      try {
        ws.send(JSON.stringify(payload));
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error("send failed"));
      }
    });
  }
}
