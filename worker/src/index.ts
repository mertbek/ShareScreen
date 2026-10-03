import type { Env } from "./signaling";

export { Signaling } from "./signaling";

/**
 * Android App Links: tells Android that this site's /join links belong to the app, so tapping
 * an invite opens it directly. The fingerprints are of the release key and of the debug key
 * used for development builds (public values).
 */
const ASSET_LINKS = [
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: "com.mertbek.sharescreen",
      sha256_cert_fingerprints: [
        "A5:88:FB:96:9E:99:89:A6:53:7B:5B:E9:8A:EE:03:22:4F:E7:D9:75:99:A2:E9:AF:88:E2:D3:8C:F3:96:92:82",
        "C2:88:CF:42:23:BE:9A:2F:D9:49:1B:47:3D:5B:C5:1C:69:05:C7:B2:80:11:AB:85:BB:D1:CF:E1:22:1E:ED:83",
      ],
    },
  },
];

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/health":
        return new Response("ok");
      case "/ws":
        if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected a WebSocket", { status: 426 });
        // One object serves every room (see Signaling).
        return env.SIGNALING.get(env.SIGNALING.idFromName("rooms")).fetch(request);
      case "/.well-known/assetlinks.json":
        return Response.json(ASSET_LINKS, { headers: { "Cache-Control": "public, max-age=3600" } });
      default:
        return env.ASSETS.fetch(request);
    }
  },
} satisfies ExportedHandler<Env>;
