import axios from "axios";
import { ElMessage } from "element-plus";
import { auth, clearAuth } from "./auth";
import { normalizeFileUrl } from "./paths";
const client = axios.create({
  baseURL: `${import.meta.env.BASE_URL}api`,
  timeout: 30000,
});
const loginPath = `${import.meta.env.BASE_URL}login`;
client.interceptors.request.use((config) => {
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`;
  return config;
});
client.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body?.code !== 0) {
      const error = new Error(body?.message || "操作失败");
      if (!response.config.silent) ElMessage.error(error.message);
      return Promise.reject(error);
    }
    return body.data;
  },
  (error) => {
    const msg =
      error.response?.data?.message ||
      (error.code === "ECONNABORTED"
        ? "请求超时，请稍后重试"
        : "暂时无法连接服务，请检查后端是否启动");
    if (!error.config?.silent) ElMessage.error(msg);
    if (error.response?.status === 401 && location.pathname !== loginPath) {
      clearAuth();
      location.href = loginPath;
    }
    return Promise.reject(error);
  },
);
export const api = {
  get: (url, params) => client.get(url, { params }),
  post: (url, data) => client.post(url, data),
  put: (url, data) => client.put(url, data),
  delete: (url) => client.delete(url),
};
export async function upload(file, purpose = "attachments") {
  const data = new FormData();
  data.append("file", file);
  data.append("purpose", purpose);
  return client.post("/files", data, { timeout: 90000 });
}
function normalizeAvatar(data) {
  return data ? { ...data, url: normalizeFileUrl(data.url) } : data;
}
export const getAvatar = (silent = false) =>
  client.get("/profile/avatar", { silent }).then(normalizeAvatar);
export async function uploadAvatar(file) {
  const data = new FormData();
  data.append("file", file);
  return client
    .post("/profile/avatar", data, { timeout: 90000 })
    .then(normalizeAvatar);
}
export async function fileUrl(id, download = false) {
  const data = await api.get(
    `/files/${id}/url`,
    download ? { download: true } : undefined,
  );
  return normalizeFileUrl(data.url);
}
