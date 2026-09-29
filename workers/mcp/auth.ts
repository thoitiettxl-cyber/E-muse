// API-key auth for the /mcp endpoint: custom EMUSE_API_KEY header or
// Authorization: Bearer, compared in constant time against the secret.

export const presentedApiKey = (request: Request): string => {
  const headerKey = request.headers.get("EMUSE_API_KEY")?.trim() || "";
  if (headerKey) return headerKey;
  const authorization = request.headers.get("authorization") || "";
  return authorization.startsWith("Bearer ")
    ? authorization.slice(7).trim()
    : "";
};

export const secretValuesEqual = async (
  left: string,
  right: string,
): Promise<boolean> => {
  if (!left || !right) return false;
  const encoder = new TextEncoder();
  const [leftHash, rightHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(left)),
    crypto.subtle.digest("SHA-256", encoder.encode(right)),
  ]);
  const leftBytes = new Uint8Array(leftHash);
  const rightBytes = new Uint8Array(rightHash);
  let difference = leftBytes.length ^ rightBytes.length;
  for (
    let index = 0;
    index < Math.min(leftBytes.length, rightBytes.length);
    index += 1
  ) {
    difference |= leftBytes[index] ^ rightBytes[index];
  }
  return difference === 0;
};

export const hasValidApiKey = async (
  request: Request,
  env: { EMUSE_API_KEY?: string },
): Promise<boolean> => {
  const expected = env.EMUSE_API_KEY?.trim() || "";
  return secretValuesEqual(presentedApiKey(request), expected);
};
