import { createRouter, createWebHistory } from "vue-router";
import { auth } from "./auth";
import Layout from "./components/Layout.vue";
import Login from "./views/Login.vue";
import Dashboard from "./views/Dashboard.vue";
import ResourcePage from "./views/ResourcePage.vue";
import Settings from "./views/Settings.vue";
import { pages } from "./pages";
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: "/login", component: Login },
    {
      path: "/",
      component: Layout,
      children: [
        { path: "", redirect: "/dashboard" },
        {
          path: "dashboard",
          component: Dashboard,
          meta: { title: "首页" },
        },
        ...Object.entries(pages).map(([key, page]) => ({
          path: key,
          component: ResourcePage,
          props: { resource: key },
          meta: { title: page.title, roles: page.roles },
        })),
        { path: "settings", component: Settings, meta: { title: "账户设置" } },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});
router.beforeEach((to) => {
  if (to.path !== "/login" && !auth.token) return "/login";
  if (to.path === "/login" && auth.token) return "/dashboard";
  if (to.meta.roles && !to.meta.roles.includes(auth.user?.role))
    return "/dashboard";
  document.title = `${to.meta.title || "登录"} · 画室学员管理系统`;
});
export default router;
