<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Menu,
  Close,
  ArrowDown,
  Calendar,
  House,
  User,
  Collection,
  Setting,
  SwitchButton,
} from "@element-plus/icons-vue";
import { auth, clearAuth, roleLabel, setUser } from "../auth";
import { api } from "../api";
import { pages } from "../pages";
import UserAvatar from "./UserAvatar.vue";
import { useAvatarSession } from "../avatar";
useAvatarSession();
const route = useRoute(),
  router = useRouter(),
  mobileOpen = ref(false);
const currentGroup = computed(() => pages[route.path.slice(1)]?.group);
const today = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  weekday: "short",
  timeZone: "Asia/Shanghai",
}).format(new Date());
const groups = computed(() => {
  const result = [];
  for (const [path, p] of Object.entries(pages)) {
    if (!p.roles.includes(auth.user?.role)) continue;
    let group = result.find((g) => g.name === p.group);
    if (!group) {
      group = { name: p.group, items: [] };
      result.push(group);
    }
    group.items.push({ path: `/${path}`, title: p.title });
  }
  return result;
});
const iconFor = (name) =>
  ({
    教务管理: User,
    教学安排: Calendar,
    教学档案: Collection,
    沟通与管理: Setting,
  })[name];
function navigate(path) {
  router.push(path);
  mobileOpen.value = false;
}
async function logout() {
  try {
    await api.post("/auth/logout");
  } catch {}
  clearAuth();
  router.replace("/login");
}
onMounted(async () => {
  try {
    setUser(await api.get("/auth/me"));
  } catch {}
});
</script>
<template>
  <div class="app-shell">
    <header class="shell-header">
      <div class="header-brand">
        <el-button
          class="mobile-toggle"
          text
          aria-label="打开导航"
          @click="mobileOpen = true"
          ><el-icon><Menu /></el-icon></el-button
        ><router-link to="/dashboard" @click="mobileOpen = false"
          >画室学员管理系统</router-link
        >
      </div>
      <div class="header-right">
        <time>{{ today }}</time
        ><el-dropdown
          trigger="click"
          @command="
            (command) =>
              command === 'logout' ? logout() : navigate('/settings')
          "
          ><button class="profile-button">
            <UserAvatar />
            <span>{{ auth.user?.displayName }}</span
            ><small>{{ roleLabel(auth.user) }}</small
            ><el-icon><ArrowDown /></el-icon></button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="settings" :icon="Setting"
                >账户设置</el-dropdown-item
              ><el-dropdown-item command="logout" :icon="SwitchButton" divided
                >退出登录</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </header>
    <div
      v-if="mobileOpen"
      class="sidebar-mask"
      @click="mobileOpen = false"
    ></div>
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="navigation-caption">
        功能导航<button
          class="mobile-close"
          aria-label="关闭导航"
          @click="mobileOpen = false"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <nav class="navigation" aria-label="主要导航">
        <button
          class="nav-item overview-link"
          :class="{ active: route.path === '/dashboard' }"
          @click="navigate('/dashboard')"
        >
          <el-icon><House /></el-icon>首页
        </button>
        <div v-for="group in groups" :key="group.name" class="nav-group">
          <div class="nav-heading">
            <el-icon><component :is="iconFor(group.name)" /></el-icon
            >{{ group.name }}
          </div>
          <button
            v-for="item in group.items"
            :key="item.path"
            class="nav-item"
            :class="{ active: route.path === item.path }"
            :aria-current="route.path === item.path ? 'page' : undefined"
            @click="navigate(item.path)"
          >
            {{ item.title }}
          </button>
        </div>
      </nav>
    </aside>
    <div class="main-shell">
      <div class="location-bar">
        <router-link to="/dashboard" @click="mobileOpen = false">首页</router-link
        ><template v-if="route.path !== '/dashboard'"
          ><span>/</span><span v-if="currentGroup">{{ currentGroup }}</span
          ><span v-if="currentGroup">/</span
          ><strong>{{ route.meta.title }}</strong></template
        >
      </div>
      <div v-if="auth.user?.readOnly" class="readonly-note">
        监护人账号：仅可查看绑定学员的资料。
      </div>
      <main class="main-content"><router-view :key="route.path" /></main>
      <footer class="page-footer">画室学员管理系统</footer>
    </div>
  </div>
</template>
