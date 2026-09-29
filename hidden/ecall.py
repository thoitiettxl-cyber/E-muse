#!/usr/bin/env python3
"""Call E-muse MCP tool with retry on 'device disconnected'."""
import json, subprocess, sys, time

MCP = ["%s/workspace/skills/mcp/bin/mcp" % __import__("os").environ["HOME"]]

def call(tool, args, retries=6, wait=8):
    payload = json.dumps(args)
    last = ""
    for i in range(retries):
        p = subprocess.run(MCP + ["call", "E-muse", tool, "--args", payload],
                           capture_output=True, text=True, timeout=120)
        out = p.stdout.strip()
        try:
            d = json.loads(out)
            txt = d["result"]["content"][0]["text"]
            if "device disconnected" in txt or "device not connected" in txt:
                last = txt; time.sleep(wait); continue
            return txt
        except Exception:
            last = out or p.stderr
            time.sleep(wait)
    raise RuntimeError("FAILED after retries: " + last[:300])

if __name__ == "__main__":
    tool = sys.argv[1]
    args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    print(call(tool, args))
