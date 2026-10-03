import { DurableObject } from "cloudflare:workers";
import { iceServers, IceEnv } from "./ice";
import { decode, encode, Message, PING_TEXT, PONG_TEXT } from "./protocol";
import {
  DEFAULT_CONFIG,
  PeerLink,
  randomPeerId,
  randomResumeToken,
  randomRoomCode,
  RoomManager,
  Snapshot,
} from "./room-manager";

export interface Env extends IceEnv {
  SIGNALING: DurableObjectNamespace<Signaling>;
  ASSETS: Fetcher;
}

/** What is remembered about a socket while the object is evicted from memory (16 KiB at most). */
interface Attachment {
  /** Identifies the connection across evictions; a member's link is compared by it. */
  connectionId: string;
  memberId?: string;
  /** A connection must say hello by then. */
  helloBy: number;
  /**
   * A one-way fingerprint of where the connection came from, never the address itself. The rooms use
   * it to tell someone guessing a PIN from everyone else.
   */
  address?: string;
}

const HELLO_TIMEOUT_MILLIS = 10_000;
const ALARM_INTERVAL_MILLIS = 5_000;
const MAX_FRAME_LENGTH = 64 * 1024;
const CONNECTIONS_PER_MINUTE = 30;
const STATE_KEY = "state";

/** The first 8 bytes of the SHA-256 of [address], as hex; undefined when the address is unknown. */
async function fingerprint(address: string | null): Promise<string | undefined> {
  if (!address) return undefined;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(address));
  return Array.from(new Uint8Array(digest).slice(0, 8), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** A live WebSocket as a [PeerLink]. */
class SocketLink implements PeerLink {
  constructor(
    readonly connectionId: string,
    public socket: WebSocket,
  ) {}

  send(message: Message): void {
    try {
      this.socket.send(encode(message));
    } catch {
      // The socket is already gone; its close event does the cleanup.
    }
  }

  close(): void {
    try {
      this.socket.close(1000, "");
    } catch {
      // Already closed.
    }
  }
}

/**
 * All signaling rooms live in this one object, so its single thread orders every message. Sockets
 * use the hibernation API: between messages the object can be evicted, which is why the rooms
 * are written to storage whenever they change and read back when it wakes.
 */
export class Signaling extends DurableObject<Env> {
  private manager!: RoomManager;
  private links = new Map<string, SocketLink>();
  private connectionTimes = new Map<string, number[]>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING_TEXT, PONG_TEXT));
    ctx.blockConcurrencyWhile(async () => {
      const snapshot = await ctx.storage.get<Snapshot>(STATE_KEY);
      this.manager = new RoomManager(
        DEFAULT_CONFIG,
        {
          newPeerId: randomPeerId,
          newRoomCode: randomRoomCode,
          newResumeToken: randomResumeToken,
          now: Date.now,
        },
        snapshot,
      );
      this.reconnectRestoredMembers();
      console.log(`signaling: loaded ${snapshot?.rooms.length ?? 0} rooms, ${this.ctx.getWebSockets().length} sockets`);
      await this.settle();
    });
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
    const address = request.headers.get("CF-Connecting-IP");
    if (!this.allowConnection(address ?? "unknown")) {
      return new Response("Too many connections", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const attachment: Attachment = {
      connectionId: crypto.randomUUID(),
      helloBy: Date.now() + HELLO_TIMEOUT_MILLIS,
      address: await fingerprint(address),
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(attachment);
    await this.scheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket: WebSocket, data: string | ArrayBuffer): Promise<void> {
    const link = this.linkFor(socket);
    if (typeof data !== "string" || data.length > MAX_FRAME_LENGTH) {
      link.send({ type: "error", code: "protocol", message: "Malformed message" });
      return;
    }
    const message = decode(data);
    const attachment = socket.deserializeAttachment() as Attachment;
    const member = attachment.memberId ? this.manager.member(attachment.memberId) : undefined;

    if (!attachment.memberId) {
      // The first message must be the hello.
      if (message?.type !== "hello") {
        link.send({ type: "error", code: "protocol", message: "Expected hello" });
        link.close();
        return;
      }
      const joined = this.manager.join(link, message, await iceServers(this.env), attachment.address ?? null);
      if (joined) socket.serializeAttachment({ ...attachment, memberId: joined.id } satisfies Attachment);
    } else if (!member) {
      // The room ended while this connection was still open.
      link.close();
      return;
    } else if (message === null) {
      link.send({ type: "error", code: "protocol", message: "Malformed message" });
    } else {
      this.manager.handle(member, message);
    }
    await this.settle();
  }

  async webSocketClose(socket: WebSocket, code: number): Promise<void> {
    await this.dropped(socket, code);
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    await this.dropped(socket, 1011);
  }

  async alarm(): Promise<void> {
    this.manager.expire();
    const now = Date.now();
    for (const socket of this.ctx.getWebSockets()) {
      const attachment = socket.deserializeAttachment() as Attachment | null;
      if (attachment && !attachment.memberId && now >= attachment.helloBy) {
        try {
          socket.close(1008, "No hello");
        } catch {
          // Already closed.
        }
      }
    }
    await this.settle();
  }

  private async dropped(socket: WebSocket, code: number): Promise<void> {
    const attachment = socket.deserializeAttachment() as Attachment | null;
    try {
      socket.close(code >= 1000 && code < 5000 && code !== 1005 && code !== 1006 ? code : 1000, "");
    } catch {
      // Already closed.
    }
    if (attachment?.memberId) {
      const member = this.manager.member(attachment.memberId);
      if (member) this.manager.disconnected(member, this.linkFor(socket));
    }
    if (attachment) this.links.delete(attachment.connectionId);
    await this.settle();
  }

  /** The stable link for [socket]: the same object for every event of one connection. */
  private linkFor(socket: WebSocket): SocketLink {
    const { connectionId } = socket.deserializeAttachment() as Attachment;
    let link = this.links.get(connectionId);
    if (!link) {
      link = new SocketLink(connectionId, socket);
      this.links.set(connectionId, link);
    }
    link.socket = socket;
    return link;
  }

  /** After waking up: connect members to the sockets that survived, and mark the rest as dropped. */
  private reconnectRestoredMembers(): void {
    for (const socket of this.ctx.getWebSockets()) {
      const attachment = socket.deserializeAttachment() as Attachment | null;
      const member = attachment?.memberId ? this.manager.member(attachment.memberId) : undefined;
      if (member) this.manager.attach(member, this.linkFor(socket));
    }
    for (const member of this.manager.allMembers()) this.manager.detachIfUnattached(member);
  }

  /** Writes the rooms if they changed and makes sure the expiry alarm is armed while it is needed. */
  private async settle(): Promise<void> {
    if (this.manager.takeDirty()) await this.ctx.storage.put(STATE_KEY, this.manager.snapshot());
    await this.scheduleAlarm();
  }

  private async scheduleAlarm(): Promise<void> {
    const needed =
      this.manager.hasDetached() ||
      this.ctx.getWebSockets().some((socket) => {
        const attachment = socket.deserializeAttachment() as Attachment | null;
        return attachment !== null && !attachment.memberId;
      });
    if (needed && (await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(Date.now() + ALARM_INTERVAL_MILLIS);
    }
  }

  private allowConnection(address: string): boolean {
    const now = Date.now();
    const recent = (this.connectionTimes.get(address) ?? []).filter((time) => now - time < 60_000);
    if (recent.length >= CONNECTIONS_PER_MINUTE) {
      this.connectionTimes.set(address, recent);
      return false;
    }
    recent.push(now);
    this.connectionTimes.set(address, recent);
    if (this.connectionTimes.size > 10_000) this.connectionTimes.clear();
    return true;
  }
}
