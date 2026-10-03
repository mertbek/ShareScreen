import type { IceServerConfig } from "./protocol";

export interface IceEnv {
  /** Cloudflare Realtime TURN key; without them only STUN is offered. */
  TURN_KEY_ID?: string;
  TURN_KEY_API_TOKEN?: string;
}

const STUN_ONLY: IceServerConfig[] = [
  { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] },
];

/** Credentials are valid for a day; fetching them again every few hours keeps every joiner's valid for hours. */
const CREDENTIAL_TTL_SECONDS = 86_400;
const REFRESH_AFTER_MILLIS = 6 * 3_600_000;
const RETRY_AFTER_FAILURE_MILLIS = 60_000;

let cached: { servers: IceServerConfig[]; refreshAt: number } | null = null;

/** STUN, and TURN relays when a key is configured, for the app's peer connections. Never throws. */
export async function iceServers(env: IceEnv, now = Date.now()): Promise<IceServerConfig[]> {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return STUN_ONLY;
  if (cached && now < cached.refreshAt) return cached.servers;
  try {
    const response = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: CREDENTIAL_TTL_SECONDS }),
      },
    );
    if (!response.ok) throw new Error(`TURN credentials: HTTP ${response.status}`);
    const body = (await response.json()) as { iceServers?: IceServerConfig[] };
    const servers = (body.iceServers ?? []).map((s) => ({
      urls: s.urls,
      ...(s.username ? { username: s.username } : {}),
      ...(s.credential ? { credential: s.credential } : {}),
    }));
    if (servers.length === 0) throw new Error("TURN credentials: empty response");
    cached = { servers, refreshAt: now + REFRESH_AFTER_MILLIS };
    return servers;
  } catch (e) {
    console.warn(String(e));
    // Keep serving what worked before; otherwise fall back to STUN, and try again soon.
    if (cached) cached.refreshAt = now + RETRY_AFTER_FAILURE_MILLIS;
    else cached = { servers: STUN_ONLY, refreshAt: now + RETRY_AFTER_FAILURE_MILLIS };
    return cached.servers;
  }
}
