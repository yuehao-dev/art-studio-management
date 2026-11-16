// Backend file tickets use /api; keep their query strings unchanged when the
// website runs below a directory. Absolute OSS signed URLs already have a host.
export function normalizeFileUrl(url, base = import.meta.env.BASE_URL) {
  return typeof url === "string" && /^\/api(?:\/|[?#]|$)/.test(url)
    ? `${base}${url.slice(1)}`
    : url;
}
