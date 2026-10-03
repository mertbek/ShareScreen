import {
  ErrorCode,
  Hello,
  IceServerConfig,
  isRouted,
  Message,
  PeerRole,
  PROTOCOL_VERSION,
  Routed,
} from "./protocol";

/** One client connection, as seen by [RoomManager]. Compared by identity. */
export interface PeerLink {
  /** Queues [message] for delivery. */
  send(message: Message): void;
  /** Closes the connection once already queued messages are delivered. */
  close(): void;
}

export interface Config {
  /** Counts the viewers that are watching; requests still waiting for the host do not count. */
  maxViewersPerRoom: number;
  /** Requests a room keeps waiting for the host's answer. */
  maxWaitingPerRoom: number;
  /**
   * Wrong PINs a room accepts per minute before it refuses the addresses that guessed wrong for a
   * while.
   */
  maxWrongPinsPerMinute: number;
  /** How long a peer whose connection dropped keeps its place, to resume over a new connection. */
  resumeGraceMillis: number;
}

export const DEFAULT_CONFIG: Config = {
  maxViewersPerRoom: 4,
  maxWaitingPerRoom: 8,
  maxWrongPinsPerMinute: 10,
  resumeGraceMillis: 30_000,
};

export interface Deps {
  newPeerId: () => string;
  newRoomCode: () => string;
  newResumeToken: () => string;
  now: () => number;
}

const MAX_DEVICE_NAME_LENGTH = 40;
const MAX_MISSED_MESSAGES = 100;
const PIN_WINDOW_MILLIS = 60_000;
/** Past this many guessing addresses the lock applies to everyone, so addresses cannot be used up. */
const MAX_GUESSING_ADDRESSES = 256;
/** How long the host's refusal keeps a device from asking again, in a room without a PIN. */
const REFUSED_WAIT_MILLIS = 60_000;

export class Member {
  approved: boolean;
  /** Null while the connection is gone and the member waits to be resumed. */
  link: PeerLink | null;
  /** When the connection dropped; meaningful while [link] is null. */
  detachedAt = 0;
  /** Messages sent while detached, delivered when the member resumes. */
  missed: Message[] = [];

  constructor(
    readonly id: string,
    readonly role: PeerRole,
    readonly deviceName: string,
    readonly roomCode: string,
    readonly resumeToken: string,
    link: PeerLink | null,
    /** Where the connection came from, when known; used to tell guessers from everyone else. */
    readonly address: string | null = null,
  ) {
    this.link = link;
    this.approved = role === "host";
  }
}

interface Room {
  code: string;
  pin: string | null;
  host: Member;
  /** When recent wrong PINs were presented, oldest first. */
  wrongPinTimes: number[];
  /** When each address last gave a wrong PIN; null stands for connections whose address is unknown. */
  wrongPinAddresses: Map<string | null, number>;
  /** When the host last turned down a request from each address, in a room without a PIN. */
  refusedAddresses: Map<string, number>;
  viewers: Map<string, Member>;
}

/** Everything that must survive the process being evicted; plain data. */
export interface Snapshot {
  rooms: {
    code: string;
    pin: string | null;
    hostId: string;
    wrongPinTimes: number[];
    /** Absent in snapshots written before addresses were tracked. */
    wrongPinAddresses?: [string | null, number][];
    refusedAddresses?: [string, number][];
    viewerIds: string[];
  }[];
  members: {
    id: string;
    role: PeerRole;
    deviceName: string;
    roomCode: string;
    resumeToken: string;
    approved: boolean;
    detachedAt: number;
    missed: Message[];
    address?: string | null;
  }[];
}

/**
 * Transport-independent signaling logic: rooms, viewer approval and relaying of WebRTC
 * negotiation between a host and its approved viewers (a port of the Kotlin RoomManager).
 *
 * A peer that leaves on purpose ("leave") is gone at once. A peer whose connection just drops
 * keeps its place for [Config.resumeGraceMillis] and can resume it with its resume token;
 * messages meanwhile are kept for it. The streams between the devices do not pass through here,
 * so they are unaffected either way.
 */
export class RoomManager {
  private rooms = new Map<string, Room>();
  private members = new Map<string, Member>();
  private byToken = new Map<string, Member>();
  /** Set by every change to what a [Snapshot] holds. */
  private dirty = false;

  constructor(
    private readonly config: Config,
    private readonly deps: Deps,
    snapshot?: Snapshot,
  ) {
    if (snapshot) this.restore(snapshot);
  }

  /** Registers a connection from its hello. Returns null (after sending an error) if refused. */
  join(link: PeerLink, hello: Hello, iceServers: IceServerConfig[], address: string | null = null): Member | null {
    this.expireDetached();
    if ((hello.protocolVersion ?? PROTOCOL_VERSION) !== PROTOCOL_VERSION) return this.refuse(link, "unsupported_version");
    if (hello.resumeToken) return this.resume(link, hello.role, hello.resumeToken, iceServers);
    const deviceName = hello.deviceName.trim().slice(0, MAX_DEVICE_NAME_LENGTH);
    return hello.role === "host"
      ? this.joinAsHost(link, hello, deviceName, iceServers)
      : this.joinAsViewer(link, hello, deviceName, iceServers, address);
  }

  handle(sender: Member, message: Message): void {
    const room = this.rooms.get(sender.roomCode);
    if (!room || room.host !== sender && room.viewers.get(sender.id) !== sender) return;
    if (message.type === "leave") {
      this.remove(room, sender);
    } else if (message.type === "join_decision" && sender.role === "host") {
      this.decide(room, message.viewerId, message.accepted);
    } else if (message.type === "kick" && sender.role === "host") {
      this.kick(room, message.viewerId);
    } else if (isRouted(message)) {
      this.relay(room, sender, message);
    } else {
      this.deliver(sender, { type: "error", code: "protocol", message: "Unexpected message" });
    }
  }

  /**
   * [link], the connection of [member], closed. Unless the member left on purpose before, it
   * waits to be resumed; a connection that was already replaced by a resumed one is ignored.
   */
  disconnected(member: Member, link: PeerLink): void {
    const room = this.rooms.get(member.roomCode);
    if (!room || (room.host !== member && room.viewers.get(member.id) !== member)) return;
    if (member.link === link) {
      member.link = null;
      member.detachedAt = this.deps.now();
      this.dirty = true;
    }
  }

  /** Ends the sessions of peers that did not resume in time. Also happens on every join. */
  expire(): void {
    this.expireDetached();
  }

  member(id: string): Member | undefined {
    return this.members.get(id);
  }

  /** Connects a member restored from a snapshot to its live connection. */
  attach(member: Member, link: PeerLink): void {
    member.link = link;
  }

  /** Marks a restored member whose connection is gone as waiting to be resumed. */
  detachIfUnattached(member: Member): void {
    if (member.link === null && member.detachedAt === 0) {
      member.detachedAt = this.deps.now();
      this.dirty = true;
    }
  }

  allMembers(): IterableIterator<Member> {
    return this.members.values();
  }

  hasDetached(): boolean {
    for (const member of this.members.values()) if (member.link === null) return true;
    return false;
  }

  /** True once after a change to persist; see [snapshot]. */
  takeDirty(): boolean {
    const dirty = this.dirty;
    this.dirty = false;
    return dirty;
  }

  snapshot(): Snapshot {
    return {
      rooms: [...this.rooms.values()].map((room) => ({
        code: room.code,
        pin: room.pin,
        hostId: room.host.id,
        wrongPinTimes: room.wrongPinTimes,
        wrongPinAddresses: [...room.wrongPinAddresses],
        refusedAddresses: [...room.refusedAddresses],
        viewerIds: [...room.viewers.keys()],
      })),
      members: [...this.members.values()].map((m) => ({
        id: m.id,
        role: m.role,
        deviceName: m.deviceName,
        roomCode: m.roomCode,
        resumeToken: m.resumeToken,
        approved: m.approved,
        detachedAt: m.detachedAt,
        missed: m.missed,
        address: m.address,
      })),
    };
  }

  private restore(snapshot: Snapshot): void {
    for (const data of snapshot.members) {
      const member = new Member(data.id, data.role, data.deviceName, data.roomCode, data.resumeToken, null, data.address ?? null);
      member.approved = data.approved;
      member.detachedAt = data.detachedAt;
      member.missed = data.missed;
      this.members.set(member.id, member);
      this.byToken.set(member.resumeToken, member);
    }
    for (const data of snapshot.rooms) {
      const host = this.members.get(data.hostId);
      if (!host) continue;
      const viewers = new Map<string, Member>();
      for (const id of data.viewerIds) {
        const viewer = this.members.get(id);
        if (viewer) viewers.set(id, viewer);
      }
      this.rooms.set(data.code, {
        code: data.code,
        pin: data.pin,
        host,
        wrongPinTimes: data.wrongPinTimes,
        wrongPinAddresses: new Map(data.wrongPinAddresses ?? []),
        refusedAddresses: new Map(data.refusedAddresses ?? []),
        viewers,
      });
    }
  }

  private joinAsHost(link: PeerLink, hello: Hello, deviceName: string, iceServers: IceServerConfig[]): Member {
    const code = this.uniqueRoomCode();
    const host = this.newMember("host", deviceName, code, link);
    this.rooms.set(code, {
      code,
      pin: hello.pin ?? null,
      host,
      wrongPinTimes: [],
      wrongPinAddresses: new Map(),
      refusedAddresses: new Map(),
      viewers: new Map(),
    });
    link.send(this.welcome(host, host.id, iceServers, false));
    this.dirty = true;
    return host;
  }

  private joinAsViewer(
    link: PeerLink,
    hello: Hello,
    deviceName: string,
    iceServers: IceServerConfig[],
    address: string | null,
  ): Member | null {
    const code = hello.roomCode?.trim().toUpperCase();
    const room = code ? this.rooms.get(code) : undefined;
    if (!room) return this.refuse(link, "room_not_found");
    // A 6-digit PIN is guessable by trying them all; the host would then only see an approval
    // request, possibly under a familiar device name. So guessing is throttled per room.
    const now = this.deps.now();
    room.wrongPinTimes = room.wrongPinTimes.filter((time) => now - time < PIN_WINDOW_MILLIS);
    for (const [guesser, time] of room.wrongPinAddresses) {
      if (now - time >= PIN_WINDOW_MILLIS) room.wrongPinAddresses.delete(guesser);
    }
    // Only the addresses that guessed wrong wait out the lock, so someone guessing cannot keep the
    // others out.
    const guessed = room.wrongPinAddresses.has(address) || room.wrongPinAddresses.size >= MAX_GUESSING_ADDRESSES;
    if (room.wrongPinTimes.length >= this.config.maxWrongPinsPerMinute && guessed) {
      return this.refuse(link, "too_many_attempts");
    }
    if (room.pin !== null && room.pin !== hello.pin) {
      room.wrongPinTimes.push(now);
      room.wrongPinAddresses.set(address, now);
      this.dirty = true;
      return this.refuse(link, "invalid_pin");
    }
    if (room.pin === null && address !== null) {
      for (const [refused, time] of room.refusedAddresses) {
        if (now - time >= REFUSED_WAIT_MILLIS) room.refusedAddresses.delete(refused);
      }
      if (room.refusedAddresses.has(address)) return this.refuse(link, "rejected");
      // Without a PIN anyone nearby can ask, so each device has one request at a time and a new one
      // replaces it.
      const earlier = this.waiting(room).find((member) => member.address === address);
      if (earlier) this.withdraw(room, earlier);
    }
    if (this.watching(room) >= this.config.maxViewersPerRoom || this.waiting(room).length >= this.config.maxWaitingPerRoom) {
      return this.refuse(link, "room_full");
    }

    const viewer = this.newMember("viewer", deviceName, room.code, link, address);
    room.viewers.set(viewer.id, viewer);
    link.send(this.welcome(viewer, room.host.id, iceServers, false));
    this.deliver(room.host, { type: "join_request", viewerId: viewer.id, deviceName });
    this.dirty = true;
    return viewer;
  }

  /** The token is a 128-bit secret, so it needs no PIN or throttling. */
  private resume(link: PeerLink, role: PeerRole, token: string, iceServers: IceServerConfig[]): Member | null {
    const member = this.byToken.get(token);
    if (!member || member.role !== role) return this.refuse(link, "resume_failed");
    const room = this.rooms.get(member.roomCode);
    if (!room) return this.refuse(link, "resume_failed");
    // The old connection may not have noticed yet that it is gone.
    member.link?.close();
    member.link = link;
    member.detachedAt = 0;
    link.send(this.welcome(member, room.host.id, iceServers, true));
    const missed = member.missed;
    member.missed = [];
    for (const message of missed) link.send(message);
    this.dirty = true;
    return member;
  }

  private decide(room: Room, viewerId: string, accepted: boolean): void {
    const viewer = room.viewers.get(viewerId);
    if (!viewer || viewer.approved) return;
    if (accepted) {
      viewer.approved = true;
      this.deliver(viewer, { type: "join_decision", viewerId, accepted: true });
      // Requests wait only while there is room for them.
      if (this.watching(room) >= this.config.maxViewersPerRoom) {
        for (const waiting of this.waiting(room)) this.turnAway(room, waiting);
      }
    } else {
      this.forget(room, viewer);
      if (room.pin === null && viewer.address !== null) room.refusedAddresses.set(viewer.address, this.deps.now());
      this.deliver(viewer, { type: "error", code: "rejected", message: "" });
      viewer.link?.close();
    }
    this.dirty = true;
  }

  /** Drops a request that its own device replaced. */
  private withdraw(room: Room, viewer: Member): void {
    this.forget(room, viewer);
    viewer.link?.close();
    this.deliver(room.host, { type: "peer_left", peerId: viewer.id });
    this.dirty = true;
  }

  /** Tells a waiting request that the room filled up before the host answered it. */
  private turnAway(room: Room, viewer: Member): void {
    this.forget(room, viewer);
    this.deliver(viewer, { type: "error", code: "room_full", message: "" });
    viewer.link?.close();
    this.deliver(room.host, { type: "peer_left", peerId: viewer.id });
    this.dirty = true;
  }

  private watching(room: Room): number {
    let count = 0;
    for (const viewer of room.viewers.values()) if (viewer.approved) count++;
    return count;
  }

  private waiting(room: Room): Member[] {
    return [...room.viewers.values()].filter((viewer) => !viewer.approved);
  }

  private kick(room: Room, viewerId: string): void {
    const viewer = room.viewers.get(viewerId);
    if (!viewer) return;
    this.forget(room, viewer);
    this.deliver(viewer, { type: "error", code: "kicked", message: "" });
    viewer.link?.close();
    this.deliver(room.host, { type: "peer_left", peerId: viewer.id });
    this.dirty = true;
  }

  /** Ends [member]'s session: a host's ends the room, a viewer's is announced to the host. */
  private remove(room: Room, member: Member): void {
    this.forget(room, member);
    if (member.role === "host") {
      for (const viewer of [...room.viewers.values()]) {
        this.forget(room, viewer);
        this.deliver(viewer, { type: "session_ended" });
        viewer.link?.close();
      }
    } else {
      this.deliver(room.host, { type: "peer_left", peerId: member.id });
    }
    this.dirty = true;
  }

  private forget(room: Room, member: Member): void {
    this.byToken.delete(member.resumeToken);
    this.members.delete(member.id);
    if (member.role === "host") this.rooms.delete(room.code);
    else room.viewers.delete(member.id);
  }

  private expireDetached(): void {
    const now = this.deps.now();
    const expired = (m: Member) => m.link === null && now - m.detachedAt >= this.config.resumeGraceMillis;
    for (const room of [...this.rooms.values()]) {
      if (expired(room.host)) {
        this.remove(room, room.host);
      } else {
        for (const viewer of [...room.viewers.values()]) if (expired(viewer)) this.remove(room, viewer);
      }
    }
  }

  /** Only host ⇄ approved viewer traffic is relayed; viewers never see each other. */
  private relay(room: Room, sender: Member, message: Routed): void {
    const target = room.host.id === message.to ? room.host : room.viewers.get(message.to);
    const allowed =
      target !== undefined &&
      (sender.role === "host" ? target.role === "viewer" && target.approved : sender.approved && target.role === "host");
    if (!target || !allowed) {
      this.deliver(sender, { type: "error", code: "protocol", message: `Cannot send to ${message.to}` });
      return;
    }
    this.deliver(target, { ...message, from: sender.id });
  }

  /** Sends now, or keeps the message for a member whose connection is gone. */
  private deliver(member: Member, message: Message): void {
    if (member.link) {
      member.link.send(message);
      return;
    }
    if (member.missed.length === MAX_MISSED_MESSAGES) member.missed.shift();
    member.missed.push(message);
    this.dirty = true;
  }

  private newMember(role: PeerRole, deviceName: string, roomCode: string, link: PeerLink, address: string | null = null): Member {
    const member = new Member(this.deps.newPeerId(), role, deviceName, roomCode, this.deps.newResumeToken(), link, address);
    this.members.set(member.id, member);
    this.byToken.set(member.resumeToken, member);
    return member;
  }

  private welcome(member: Member, hostId: string, iceServers: IceServerConfig[], resumed: boolean): Message {
    return {
      type: "welcome",
      peerId: member.id,
      roomCode: member.roomCode,
      hostId,
      iceServers,
      resumeToken: member.resumeToken,
      resumed,
    };
  }

  private uniqueRoomCode(): string {
    let code: string;
    do {
      code = this.deps.newRoomCode();
    } while (this.rooms.has(code));
    return code;
  }

  private refuse(link: PeerLink, code: ErrorCode): null {
    link.send({ type: "error", code, message: "" });
    link.close();
    return null;
  }
}

// No 0/O, 1/I: codes are read aloud and typed by hand.
const ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export function randomRoomCode(length = 6): string {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return Array.from(bytes, (b) => ROOM_CODE_ALPHABET[b % ROOM_CODE_ALPHABET.length]).join("");
}

export function randomResumeToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function randomPeerId(): string {
  return crypto.randomUUID();
}
