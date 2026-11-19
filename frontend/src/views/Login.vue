<script setup>
import { ref, reactive } from "vue";
import { useRouter } from "vue-router";
import { User, Calendar, Collection } from "@element-plus/icons-vue";
import { api } from "../api";
import { setAuth } from "../auth";
const form = reactive({ username: "", password: "" }),
  busy = ref(false),
  formRef = ref(),
  router = useRouter();
const rules = {
  username: [{ required: true, message: "请输入登录账号", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};
async function login() {
  if (!(await formRef.value.validate().catch(() => false))) return;
  busy.value = true;
  try {
    const r = await api.post("/auth/login", form);
    setAuth(r.token, r.user);
    router.replace("/dashboard");
  } catch {
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <header class="login-header">
      <span class="brand-mark"
        ><el-icon><Collection /></el-icon
      ></span>
      <strong>画室学员管理系统</strong>
    </header>
    <main class="login-main">
      <section class="login-intro">
        <span class="login-kicker">画室教学 · 日常管理</span>
        <h1>教学有安排，<br />成长有记录。</h1>
        <p>从学员入班到作品点评，<br />有序管理画室的每一天。</p>
        <div class="login-features">
          <div>
            <el-icon><User /></el-icon
            ><span
              ><strong>学员与班级</strong
              ><small>档案、报名、转班，一处管理</small></span
            >
          </div>
          <div>
            <el-icon><Calendar /></el-icon
            ><span
              ><strong>课表与考勤</strong
              ><small>课程安排、请假与补课，有据可查</small></span
            >
          </div>
          <div>
            <el-icon><Collection /></el-icon
            ><span
              ><strong>作品与成长</strong
              ><small>保留创作版本，记录每一次进步</small></span
            >
          </div>
        </div>
      </section>
      <section class="login-box">
        <h1>用户登录</h1>
        <p>请输入画室为你开通的账号</p>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="login"
          ><el-form-item label="账号" prop="username"
            ><el-input
              v-model="form.username"
              autocomplete="username"
              placeholder="请输入账号"
              maxlength="60" /></el-form-item
          ><el-form-item label="密码" prop="password"
            ><el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="请输入密码"
              maxlength="100" /></el-form-item
          ><el-button
            type="primary"
            class="login-submit"
            native-type="submit"
            :loading="busy"
            >登录</el-button
          ></el-form
        >
        <p class="login-contact">账号由画室统一开通。忘记密码请联系管理员。</p>
      </section>
    </main>
    <footer class="login-footer">画室学员管理系统</footer>
  </div>
</template>
