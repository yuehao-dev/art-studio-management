import { reactive } from "vue";
function readUser() {
  try {
    return JSON.parse(sessionStorage.getItem("studio-user") || "null");
  } catch {
    return null;
  }
}
export const auth = reactive({
  token: sessionStorage.getItem("studio-token") || "",
  user: readUser(),
});
export function setAuth(token, user) {
  auth.token = token;
  auth.user = user;
  sessionStorage.setItem("studio-token", token);
  sessionStorage.setItem("studio-user", JSON.stringify(user));
}
export function setUser(user) {
  auth.user = user;
  sessionStorage.setItem("studio-user", JSON.stringify(user));
}
export function clearAuth() {
  auth.token = "";
  auth.user = null;
  sessionStorage.removeItem("studio-token");
  sessionStorage.removeItem("studio-user");
}
export const roleLabel = (u) =>
  u?.readOnly
    ? "监护人 · 只读"
    : { ADMIN: "管理员", TEACHER: "教师", STUDENT: "学员" }[u?.role] || "访客";
