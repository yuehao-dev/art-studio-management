import { reactive, watch, onMounted, onBeforeUnmount } from "vue";
import { auth } from "./auth";
import { api, getAvatar, uploadAvatar } from "./api";

// Signed image URLs stay in memory and are renewed before their five-minute expiry.
export const avatar = reactive({
  fileId: null,
  url: "",
  loading: false,
  saving: false,
  failed: false,
  revision: 0,
});
let generation = 0;
let refreshTimer;
let refreshAt = 0;
let imageRetried = false;

function clearTimer() {
  clearTimeout(refreshTimer);
  refreshTimer = undefined;
}
function clearAvatar() {
  generation++;
  clearTimer();
  refreshAt = 0;
  imageRetried = false;
  Object.assign(avatar, {
    fileId: null, url: "", loading: false, saving: false, failed: false,
    revision: avatar.revision + 1,
  });
}
function scheduleRefresh() {
  clearTimer();
  if (!avatar.url || !auth.token) return;
  refreshTimer = setTimeout(() => {
    if (document.visibilityState === "visible") refreshAvatar(true);
  }, Math.max(1000, refreshAt - Date.now()));
}
function applyAvatar(data, resetRetry = true) {
  avatar.fileId = data.fileId ?? null;
  avatar.url = data.url || "";
  avatar.failed = false;
  avatar.revision++;
  if (resetRetry) imageRetried = false;
  refreshAt = Date.now() + Math.max(30, (Number(data.expiresIn) || 300) - 60) * 1000;
  scheduleRefresh();
}
export async function refreshAvatar(silent = false, resetRetry = true) {
  if (!auth.token || avatar.loading || avatar.saving) return;
  const token = auth.token;
  const request = ++generation;
  avatar.loading = true;
  clearTimer();
  try {
    const data = await getAvatar(silent);
    if (request === generation && token === auth.token) applyAvatar(data, resetRetry);
  } catch {
    if (request === generation && token === auth.token) {
      avatar.url = "";
      avatar.failed = true;
    }
  } finally {
    if (request === generation) avatar.loading = false;
  }
}
export function avatarImageFailed(url, revision) {
  if (url !== avatar.url || revision !== avatar.revision) return;
  avatar.url = "";
  clearTimer();
  if (!imageRetried) {
    imageRetried = true;
    refreshAvatar(true, false);
  } else avatar.failed = true;
}
async function changeAvatar(request) {
  if (!auth.token || avatar.saving) return false;
  const token = auth.token;
  const current = ++generation;
  clearTimer();
  avatar.loading = false;
  avatar.saving = true;
  let completed = false;
  try {
    const data = await request();
    if (current !== generation || token !== auth.token) return false;
    applyAvatar(data);
    completed = true;
    return true;
  } finally {
    if (current === generation) {
      avatar.saving = false;
      if (completed) scheduleRefresh();
      else refreshAvatar(true);
    }
  }
}
export const saveAvatar = (file) => changeAvatar(() => uploadAvatar(file));
export const removeAvatar = () => changeAvatar(() => api.delete("/profile/avatar"));

export function useAvatarSession() {
  const stopWatch = watch(() => auth.token, () => {
    clearAvatar();
    if (auth.token) refreshAvatar(true);
  }, { immediate: true, flush: "sync" });
  function onVisible() {
    if (document.visibilityState === "visible" && avatar.fileId && Date.now() >= refreshAt)
      refreshAvatar(true);
  }
  onMounted(() => document.addEventListener("visibilitychange", onVisible));
  onBeforeUnmount(() => {
    stopWatch();
    document.removeEventListener("visibilitychange", onVisible);
    clearAvatar();
  });
}
