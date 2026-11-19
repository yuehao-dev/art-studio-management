<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Menu,
  Close,
  ArrowDown,
  ArrowRight,
  Calendar,
  House,
  User,
  Collection,
  Setting,
  SwitchButton,
  Search,
  Fold,
  Expand,
} from "@element-plus/icons-vue";
import { auth, clearAuth, roleLabel, setUser } from "../auth";
import { api } from "../api";
import { pages } from "../pages";
import UserAvatar from "./UserAvatar.vue";
import { useAvatarSession } from "../avatar";
useAvatarSession();
const route = useRoute(),
  router = useRouter();
const mobileOpen = ref(false),
  compact = ref(false),
  query = ref("");
const expandedGroup = ref("");
const currentGroup = computed(() => pages[route.path.slice(1)]?.group);
watch(
  currentGroup,
  (group) => {
    if (group) expandedGroup.value = group;
  },
  { immediate: true },
);
watch(
  () => route.path,
  () => {
    mobileOpen.value = false;
    query.value = "";
  },
);
const groups = computed(() => {
  const result = [];
  for (const [path, p] of Object.entries(pages)) {
    if (!p.roles.includes(auth.user?.role)) continue;
    if (
      query.value.trim() &&
      !`${p.title}${p.group}`.includes(query.value.trim())
    )
      continue;
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
function toggleGroup(name) {
  compact.value = false;
  expandedGroup.value = expandedGroup.value === name ? "" : name;
}
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
  <div class="app-shell" :class="{ 'sidebar-compact': compact }">
    <a href="#main-content" class="skip-link">跳到主要内容</a>
    <header class="shell-header">
      <div class="header-brand">
        <el-button
          class="mobile-toggle"
          text
          aria-label="打开导航"
          @click="
            compact = false;
            mobileOpen = true;
          "
          ><el-icon><Menu /></el-icon
        ></el-button>
        <router-link to="/dashboard" class="brand-link"
          ><span class="brand-mark"
            ><el-icon><Collection /></el-icon></span
          ><span>画室学员管理系统</span></router-link
        >
      </div>
      <div class="header-context">
        <el-button
          class="desktop-toggle"
          text
          :aria-label="compact ? '展开侧栏' : '收起侧栏'"
          @click="compact = !compact"
          ><el-icon><Expand v-if="compact" /><Fold v-else /></el-icon
        ></el-button>
        <nav class="location-bar" aria-label="当前位置">
          <router-link to="/dashboard">首页</router-link>
          <template v-if="route.path !== '/dashboard'"
            ><el-icon><ArrowRight /></el-icon
            ><span v-if="currentGroup">{{ currentGroup }}</span
            ><el-icon v-if="currentGroup"><ArrowRight /></el-icon
            ><strong>{{ route.meta.title }}</strong></template
          >
        </nav>
      </div>
      <div class="header-right">
        <el-dropdown
          trigger="click"
          @command="
            (command) =>
              command === 'logout' ? logout() : navigate('/settings')
          "
        >
          <button class="profile-button" aria-label="账户菜单">
            <UserAvatar /><span class="profile-name"
              >{{ auth.user?.displayName
              }}<small>{{ roleLabel(auth.user) }}</small></span
            ><el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="settings" :icon="Setting"
                >账户设置</el-dropdown-item
              ><el-dropdown-item command="logout" :icon="SwitchButton" divided
                >退出登录</el-dropdown-item
              ></el-dropdown-menu
            ></template
          >
        </el-dropdown>
      </div>
    </header>
    <div
      v-if="mobileOpen"
      class="sidebar-mask"
      @click="mobileOpen = false"
    ></div>
    <aside class="sidebar" :class="{ open: mobileOpen }" aria-label="功能导航">
      <div class="navigation-caption">
        <span>工作空间</span
        ><button
          class="mobile-close"
          aria-label="关闭导航"
          @click="mobileOpen = false"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <div v-if="!compact" class="nav-search">
        <el-input
          v-model="query"
          :prefix-icon="Search"
          placeholder="查找功能"
          aria-label="查找功能"
          clearable
        />
      </div>
      <nav class="navigation" aria-label="主要导航">
        <button
          v-show="!query || '首页'.includes(query)"
          class="nav-item overview-link"
          :class="{ active: route.path === '/dashboard' }"
          :aria-current="route.path === '/dashboard' ? 'page' : undefined"
          title="首页"
          @click="navigate('/dashboard')"
        >
          <el-icon><House /></el-icon><span>首页</span>
        </button>
        <div
          v-for="(group, index) in groups"
          :key="group.name"
          class="nav-group"
        >
          <button
            class="nav-heading"
            :class="{ 'group-active': currentGroup === group.name }"
            :aria-expanded="
              !compact && (!!query || expandedGroup === group.name)
            "
            :aria-controls="`nav-group-${index}`"
            :title="group.name"
            @click="toggleGroup(group.name)"
          >
            <el-icon><component :is="iconFor(group.name)" /></el-icon
            ><span>{{ group.name }}</span
            ><el-icon
              class="group-chevron"
              :class="{
                expanded: !compact && (!!query || expandedGroup === group.name),
              }"
              ><ArrowRight
            /></el-icon>
          </button>
          <div
            v-show="!compact && (!!query || expandedGroup === group.name)"
            :id="`nav-group-${index}`"
            class="nav-children"
          >
            <button
              v-for="item in group.items"
              :key="item.path"
              class="nav-item"
              :class="{ active: route.path === item.path }"
              :aria-current="route.path === item.path ? 'page' : undefined"
              @click="navigate(item.path)"
            >
              <span>{{ item.title }}</span>
            </button>
          </div>
        </div>
        <p
          v-if="query && !groups.length && !'首页'.includes(query)"
          class="nav-no-results"
        >
          未找到相关功能
        </p>
      </nav>
      <button
        class="sidebar-settings"
        title="账户设置"
        @click="navigate('/settings')"
      >
        <el-icon><Setting /></el-icon><span>账户设置</span>
      </button>
    </aside>
    <div class="main-shell">
      <div v-if="auth.user?.readOnly" class="readonly-note">
        监护人账号 · 仅可查看绑定学员的教学资料
      </div>
      <main id="main-content" class="main-content" tabindex="-1">
        <router-view :key="route.path" />
      </main>
      <footer class="page-footer">画室学员管理系统</footer>
    </div>
  </div>
</template>
