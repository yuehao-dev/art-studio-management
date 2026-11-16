<script setup>
import { ref, reactive } from "vue";
import { useRouter } from "vue-router";
import { User, Lock } from "@element-plus/icons-vue";
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
      <strong>画室学员管理系统</strong>
    </header>
    <main class="login-main">
      <section class="login-box">
        <h1>用户登录</h1>
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
