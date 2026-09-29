// E-muse shared protocol: commands the Worker sends to the Android app
// over the device WebSocket, and results the app sends back.

/** Command names understood by the E-Muse Android app. */
export type Cmd =
  | "device.info"
  | "shell.exec"
  | "app.list"
  | "app.info"
  | "app.install"
  | "app.uninstall"
  | "app.start"
  | "app.stop"
  | "file.list"
  | "file.pull"
  | "file.push"
  | "file.delete"
  | "screen.capture"
  | "input.tap"
  | "input.swipe"
  | "input.key"
  | "input.text"
  | "ui.dump";

/** Worker -> app. */
export interface DeviceCommand {
  id: string;
  cmd: Cmd;
  args: Record<string, unknown>;
}

/** App -> worker. The "type" discriminator lets the Worker tell results apart. */
export interface DeviceResult {
  type: "result";
  id: string;
  ok: boolean;
  result?: unknown;
  error?: string;
}

/** App -> worker, first message after the WebSocket opens. */
export interface HelloMessage {
  type: "hello";
  deviceId: string;
  deviceName: string;
  token: string;
}

export interface DeviceInfo {
  deviceId: string;
  deviceName: string;
  connectedAt: string;
}

export interface EmuseEnv {
  EMUSE_API_KEY?: string;
  EMUSE_READ_DISABLED?: string;
  EMUSE_WRITE_DISABLED?: string;
  EMUSE_CHANNEL_DISABLED?: string;
  DEVICE_LINK: DurableObjectNamespace;
}

/** Abstraction the MCP handler uses to reach connected devices. */
export interface DeviceGateway {
  listDevices(): Promise<DeviceInfo[]>;
  dispatch(
    deviceId: string | undefined,
    cmd: Cmd,
    args: Record<string, unknown>,
    timeoutMs?: number,
  ): Promise<unknown>;
  /** Tool on/off flags: tool name -> enabled. tool_flags itself is always on. */
  getToolFlags(): Promise<Record<string, boolean>>;
  /** Merge updates (unknown names and tool_flags are ignored). Returns new flags. */
  setToolFlags(updates: Record<string, boolean>): Promise<Record<string, boolean>>;
}
