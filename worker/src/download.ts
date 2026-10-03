const REPOSITORY = "mertbek/ShareScreen";
const LATEST_RELEASE = `https://api.github.com/repos/${REPOSITORY}/releases/latest`;
const DOWNLOADS = `https://github.com/${REPOSITORY}/releases/download/`;

/** Where people land when the newest release cannot be looked up. */
export const RELEASES_PAGE = `https://github.com/${REPOSITORY}/releases/latest`;

const CACHE_MILLIS = 10 * 60_000;

let cached: { release: unknown; at: number } | undefined;

/**
 * The address of the full or the lite APK in a GitHub release, or null. Only files of this
 * repository's releases qualify, so a reply that was tampered with cannot send anyone elsewhere.
 */
export function apkUrl(release: unknown, lite: boolean): string | null {
  const assets = (release as { assets?: unknown } | null)?.assets;
  if (!Array.isArray(assets)) return null;
  for (const asset of assets) {
    const { name, browser_download_url: url } = (asset ?? {}) as { name?: unknown; browser_download_url?: unknown };
    if (typeof name !== "string" || typeof url !== "string") continue;
    if (name.endsWith(".apk") && name.endsWith("-lite.apk") === lite && url.startsWith(DOWNLOADS)) return url;
  }
  return null;
}

async function latestRelease(): Promise<unknown> {
  if (cached && Date.now() - cached.at < CACHE_MILLIS) return cached.release;
  try {
    const response = await fetch(LATEST_RELEASE, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "sharescreen-worker" },
    });
    if (response.ok) {
      cached = { release: await response.json(), at: Date.now() };
    }
  } catch {
    // Fall back to the last answer, if any.
  }
  return cached?.release;
}

/** Sends the visitor to the APK of the newest release, or to the release page when there is none. */
export async function redirectToApk(lite: boolean): Promise<Response> {
  const url = apkUrl(await latestRelease(), lite);
  return Response.redirect(url ?? RELEASES_PAGE, 302);
}
