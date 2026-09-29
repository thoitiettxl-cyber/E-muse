// MCP tool definitions for E-muse. Every tool maps to a device Cmd
// (see protocol.ts), except device_list which is served by the Worker.

import type { Cmd } from "./protocol.ts";

export interface ToolAnnotations {
  readOnlyHint: boolean;
  destructiveHint: boolean;
  idempotentHint: boolean;
  openWorldHint: boolean;
}

export interface ToolDef {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations: ToolAnnotations;
  kind: "query" | "write";
  cmd?: Cmd;
}

const DEVICE_ID_PROP = {
  deviceId: {
    type: "string",
    description:
      "Target device id. Optional when exactly one device is connected; required when several are.",
  },
};

const withDevice = (
  properties: Record<string, unknown>,
  required: string[] = [],
): Record<string, unknown> => ({
  type: "object",
  properties: { ...DEVICE_ID_PROP, ...properties },
  required,
  additionalProperties: false,
});

const RO: ToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};
const W: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: false,
};
const DANGEROUS: ToolAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: false,
  openWorldHint: false,
};

export const TOOL_DEFS: ToolDef[] = [
  {
    name: "device_list",
    description:
      "List Android devices currently connected to the E-muse Worker via WebSocket.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
    annotations: RO,
    kind: "query",
  },
  {
    name: "device_info",
    description:
      "Get device details: model, Android version, SDK level, root availability, accessibility/screen-capture readiness.",
    inputSchema: withDevice({}),
    annotations: RO,
    kind: "query",
    cmd: "device.info",
  },
  {
    name: "shell_exec",
    description:
      "Execute a shell command on the device. Runs as the app user via 'sh -c' by default; set asRoot to run via 'su -c' when the device is rooted.",
    inputSchema: withDevice(
      {
        command: { type: "string", description: "Shell command to run." },
        asRoot: {
          type: "boolean",
          description: "Run with su (requires a rooted device). Default false.",
        },
        timeoutMs: {
          type: "number",
          description: "Max wait in ms (1000-120000). Default 30000.",
        },
      },
      ["command"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "shell.exec",
  },
  {
    name: "app_list",
    description: "List installed applications (package, label, system flag).",
    inputSchema: withDevice({
      system: {
        type: "boolean",
        description: "Include system apps. Default false.",
      },
    }),
    annotations: RO,
    kind: "query",
    cmd: "app.list",
  },
  {
    name: "app_info",
    description:
      "Get details about one installed package: label, versionName, versionCode, system flag.",
    inputSchema: withDevice(
      { package: { type: "string", description: "Application package name." } },
      ["package"],
    ),
    annotations: RO,
    kind: "query",
    cmd: "app.info",
  },
  {
    name: "app_install",
    description:
      "Install an APK on the device from base64-encoded bytes. Returns a PackageInstaller session id; the system shows a user confirmation dialog.",
    inputSchema: withDevice(
      {
        apkBase64: {
          type: "string",
          description: "APK file bytes, base64-encoded.",
        },
      },
      ["apkBase64"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "app.install",
  },
  {
    name: "app_uninstall",
    description:
      "Uninstall an application. Opens the system uninstall dialog, so the user must confirm.",
    inputSchema: withDevice(
      { package: { type: "string", description: "Application package name." } },
      ["package"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "app.uninstall",
  },
  {
    name: "app_start",
    description:
      "Launch an application: by package (its launcher intent) or by explicit action/uri with optional string extras.",
    inputSchema: withDevice({
      package: {
        type: "string",
        description: "Application package name (used when action is omitted).",
      },
      action: { type: "string", description: "Intent action, e.g. android.intent.action.VIEW." },
      uri: { type: "string", description: "Intent data URI." },
      extras: {
        type: "object",
        description: "String extras to attach to the intent.",
        additionalProperties: { type: "string" },
      },
    }),
    annotations: W,
    kind: "write",
    cmd: "app.start",
  },
  {
    name: "app_stop",
    description:
      "Force-stop an application (am force-stop). Requires a rooted device.",
    inputSchema: withDevice(
      { package: { type: "string", description: "Application package name." } },
      ["package"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "app.stop",
  },
  {
    name: "file_list",
    description: "List a directory on the device (name, path, isDir, size, modified).",
    inputSchema: withDevice(
      { path: { type: "string", description: "Absolute directory path." } },
      ["path"],
    ),
    annotations: RO,
    kind: "query",
    cmd: "file.list",
  },
  {
    name: "file_pull",
    description:
      "Read a file from the device. Returns name, size and base64 content (max 10MB).",
    inputSchema: withDevice(
      { path: { type: "string", description: "Absolute file path." } },
      ["path"],
    ),
    annotations: RO,
    kind: "query",
    cmd: "file.pull",
  },
  {
    name: "file_push",
    description:
      "Write base64-encoded bytes to a path on the device. Uses root fallback for paths outside the app sandbox.",
    inputSchema: withDevice(
      {
        path: { type: "string", description: "Absolute destination path." },
        base64: { type: "string", description: "File bytes, base64-encoded." },
        mode: {
          type: "string",
          description: "Optional chmod mode, e.g. '644'.",
        },
      },
      ["path", "base64"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "file.push",
  },
  {
    name: "file_delete",
    description: "Delete a file or directory on the device.",
    inputSchema: withDevice(
      { path: { type: "string", description: "Absolute path." } },
      ["path"],
    ),
    annotations: DANGEROUS,
    kind: "write",
    cmd: "file.delete",
  },
  {
    name: "screen_capture",
    description:
      "Take a screenshot. Returns PNG bytes as an image content block plus width/height. Needs root or a granted MediaProjection session.",
    inputSchema: withDevice({}),
    annotations: RO,
    kind: "query",
    cmd: "screen.capture",
  },
  {
    name: "input_tap",
    description: "Tap the screen at (x, y) in pixels.",
    inputSchema: withDevice(
      {
        x: { type: "number", description: "X coordinate in pixels." },
        y: { type: "number", description: "Y coordinate in pixels." },
      },
      ["x", "y"],
    ),
    annotations: W,
    kind: "write",
    cmd: "input.tap",
  },
  {
    name: "input_swipe",
    description: "Swipe from (x1, y1) to (x2, y2) in pixels over durationMs milliseconds.",
    inputSchema: withDevice(
      {
        x1: { type: "number" },
        y1: { type: "number" },
        x2: { type: "number" },
        y2: { type: "number" },
        durationMs: {
          type: "number",
          description: "Gesture duration in ms. Default 300.",
        },
      },
      ["x1", "y1", "x2", "y2"],
    ),
    annotations: W,
    kind: "write",
    cmd: "input.swipe",
  },
  {
    name: "input_key",
    description:
      "Send a key event by Android key code (e.g. 3 = HOME, 4 = BACK, 66 = ENTER).",
    inputSchema: withDevice(
      { keyCode: { type: "number", description: "Android KeyEvent key code." } },
      ["keyCode"],
    ),
    annotations: W,
    kind: "write",
    cmd: "input.key",
  },
  {
    name: "input_text",
    description:
      "Type text into the currently focused input field (accessibility) or via the 'input text' shell command.",
    inputSchema: withDevice(
      { text: { type: "string", description: "Text to type." } },
      ["text"],
    ),
    annotations: W,
    kind: "write",
    cmd: "input.text",
  },
  {
    name: "ui_dump",
    description:
      "Dump the current UI hierarchy as JSON (class, text, content description, bounds, children). Needs the accessibility service, or root as fallback.",
    inputSchema: withDevice({}),
    annotations: RO,
    kind: "query",
    cmd: "ui.dump",
  },
  {
    name: "tool_flags",
    description:
      "Get or change which E-muse tools are enabled. With no arguments, returns the on/off state of every tool. Pass {set: {shell_exec: false, file_push: false}} to disable tools, or {reset: true} to re-enable all. Disabled tools are hidden from tools/list and rejected on tools/call. This tool is always available and cannot be disabled. Note: the EMUSE_WRITE_DISABLED kill-switch is still enforced at call time regardless of flags.",
    inputSchema: {
      type: "object",
      properties: {
        set: {
          type: "object",
          description: "Map of tool name to enabled flag, e.g. {shell_exec: false}.",
          additionalProperties: { type: "boolean" },
        },
        reset: {
          type: "boolean",
          description: "Re-enable every tool.",
        },
      },
      additionalProperties: false,
    },
    annotations: RO,
    kind: "query",
  },
];

export const toolByName: Map<string, ToolDef> = new Map(
  TOOL_DEFS.map((tool) => [tool.name, tool]),
);
