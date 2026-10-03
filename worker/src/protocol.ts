/**
 * The signaling protocol, as spoken by the Android app (see signaling/Protocol.kt): JSON text
 * frames with a "type" discriminator. Keep in step with the Kotlin side.
 */

export const PROTOCOL_VERSION = 1;

export type PeerRole = "host" | "viewer";

export type ErrorCode =
  | "protocol"
  | "unsupported_version"
  | "unauthorized"
  | "host_exists"
  | "room_not_found"
  | "invalid_pin"
  | "too_many_attempts"
  | "room_full"
  | "rejected"
  | "kicked"
  | "resume_failed";

export interface IceServerConfig {
  urls: string[];
  username?: string;
  credential?: string;
}

export type Hello = {
  type: "hello";
  role: PeerRole;
  deviceName: string;
  pin?: string;
  roomCode?: string;
  hostSecret?: string;
  resumeToken?: string;
  protocolVersion?: number;
};

export type Message =
  | Hello
  | {
      type: "welcome";
      peerId: string;
      roomCode: string;
      hostId: string;
      iceServers: IceServerConfig[];
      resumeToken: string;
      resumed: boolean;
    }
  | { type: "join_request"; viewerId: string; deviceName: string }
  | { type: "join_decision"; viewerId: string; accepted: boolean }
  | { type: "kick"; viewerId: string }
  | { type: "offer" | "answer"; to: string; sdp: string; from: string }
  | { type: "ice"; to: string; candidate: string; sdpMid: string | null; sdpMLineIndex: number; from: string }
  | { type: "peer_left"; peerId: string }
  | { type: "session_ended" }
  | { type: "leave" }
  | { type: "error"; code: ErrorCode; message: string };

/** Messages relayed between a host and one of its viewers. */
export type Routed = Extract<Message, { type: "offer" | "answer" | "ice" }>;

export function isRouted(message: Message): message is Routed {
  return message.type === "offer" || message.type === "answer" || message.type === "ice";
}

/** Serializes with "type" first, which every reader of the protocol handles. */
export function encode(message: Message): string {
  const { type, ...rest } = message;
  return JSON.stringify({ type, ...rest });
}

/** Null for anything that is not a well-formed message of a known type. */
export function decode(text: string): Message | null {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const o = value as Record<string, unknown>;
  const str = (key: string): string | undefined => (typeof o[key] === "string" ? (o[key] as string) : undefined);

  switch (o.type) {
    case "hello": {
      const role = o.role;
      if (role !== "host" && role !== "viewer") return null;
      return {
        type: "hello",
        role,
        deviceName: str("deviceName") ?? "",
        pin: str("pin"),
        roomCode: str("roomCode"),
        hostSecret: str("hostSecret"),
        resumeToken: str("resumeToken"),
        protocolVersion: typeof o.protocolVersion === "number" ? o.protocolVersion : PROTOCOL_VERSION,
      };
    }
    case "join_decision": {
      const viewerId = str("viewerId");
      if (viewerId === undefined || typeof o.accepted !== "boolean") return null;
      return { type: "join_decision", viewerId, accepted: o.accepted };
    }
    case "kick": {
      const viewerId = str("viewerId");
      return viewerId === undefined ? null : { type: "kick", viewerId };
    }
    case "offer":
    case "answer": {
      const to = str("to");
      const sdp = str("sdp");
      return to === undefined || sdp === undefined ? null : { type: o.type, to, sdp, from: "" };
    }
    case "ice": {
      const to = str("to");
      const candidate = str("candidate");
      if (to === undefined || candidate === undefined || typeof o.sdpMLineIndex !== "number") return null;
      return { type: "ice", to, candidate, sdpMid: str("sdpMid") ?? null, sdpMLineIndex: o.sdpMLineIndex, from: "" };
    }
    case "leave":
      return { type: "leave" };
    default:
      // Clients never send the other types; unknown ones are refused like malformed ones.
      return null;
  }
}
