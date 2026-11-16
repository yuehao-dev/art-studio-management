<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { auth, roleLabel, clearAuth } from "../auth";
import { api } from "../api";
import UserAvatar from "../components/UserAvatar.vue";
import { avatar, saveAvatar, removeAvatar, refreshAvatar } from "../avatar";
const avatarInput = ref();
const form = reactive({ oldPassword: "", newPassword: "", confirm: "" }),
  formRef = ref(),
  busy = ref(false),
  router = useRouter();
const profile = ref(null);
async function selectAvatar(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file || avatar.saving) return;
  if (!/\.(jpe?g|png)$/i.test(file.name) || !["image/jpeg", "image/png"].includes(file.type)) {
    ElMessage.warning("请选择 JPG 或 PNG 图片");
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning("头像不能超过 2 MB");
    return;
  }
  try {
    if (await saveAvatar(file)) ElMessage.success("头像已更新");
  } catch {}
}
async function resetAvatar() {
  try {
    if (await removeAvatar()) ElMessage.success("已恢复默认头像");
  } catch {}
}
onMounted(async () => {
  if (auth.user?.role === "STUDENT") {
    try {
      profile.value = (await api.get("/students"))[0];
    } catch {}
  }
});
const rules = {
  oldPassword: [{ required: true, message: "请输入当前密码", trigger: "blur" }],
  newPassword: [
    { required: true, min: 8, message: "新密码至少 8 位", trigger: "blur" },
  ],
  confirm: [
    {
      validator: (_, v, cb) =>
        v === form.newPassword ? cb() : cb(new Error("两次新密码不一致")),
      trigger: "blur",
    },
  ],
};
async function submit() {
  if (!(await formRef.value.validate().catch(() => false))) return;
  busy.value = true;
  try {
    await api.put("/auth/password", {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    });
    ElMessage.success("密码已修改，请重新登录");
    clearAuth();
    router.replace("/login");
  } catch {
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <h1>账户设置</h1>
        <p>设置个人头像，查看账户信息，修改登录密码。</p>
      </div>
    </div>
    <section class="panel settings-panel">
      <h2>个人头像</h2>
      <div class="avatar-settings">
        <UserAvatar :size="72" alt="当前头像" />
        <div class="avatar-controls">
          <div class="avatar-actions">
            <el-button type="primary" :loading="avatar.saving" @click="avatarInput?.click()">更换头像</el-button>
            <el-button :disabled="!avatar.fileId || avatar.saving || avatar.loading" @click="resetAvatar">恢复默认</el-button>
            <input ref="avatarInput" class="avatar-file-input" type="file" accept=".jpg,.jpeg,.png"
              aria-label="选择头像文件" :disabled="avatar.saving" @change="selectAvatar" />
          </div>
          <p>支持 JPG、PNG，不超过 2 MB，建议使用正方形图片。</p>
          <div v-if="avatar.failed" class="avatar-status" role="status">
            <span>头像加载失败</span><el-button link type="primary" :loading="avatar.loading"
              :disabled="avatar.saving" @click="refreshAvatar()">重新加载</el-button>
          </div>
          <p v-else-if="avatar.loading" role="status">正在加载头像…</p>
        </div>
      </div>
      <h2>当前账户</h2>
      <el-descriptions :column="1" border
        ><el-descriptions-item label="登录账号">{{
          auth.user?.username
        }}</el-descriptions-item
        ><el-descriptions-item label="显示姓名">{{
          auth.user?.displayName
        }}</el-descriptions-item
        ><el-descriptions-item label="身份">{{
          roleLabel(auth.user)
        }}</el-descriptions-item></el-descriptions
      ><template v-if="profile"
        ><h2 class="mt-32">学员资料</h2>
        <el-descriptions :column="1" border
          ><el-descriptions-item label="姓名">{{
            profile.name
          }}</el-descriptions-item
          ><el-descriptions-item label="出生日期">{{
            profile.birthday || "—"
          }}</el-descriptions-item
          ><el-descriptions-item label="联系电话">{{
            profile.phone || "—"
          }}</el-descriptions-item
          ><el-descriptions-item label="监护人">{{
            profile.guardianName || "—"
          }}</el-descriptions-item
          ><el-descriptions-item label="监护人电话">{{
            profile.guardianPhone || "—"
          }}</el-descriptions-item></el-descriptions
        >
        <p class="muted mt-24">
          资料如有变更，请联系画室管理员更新。
        </p></template
      >
      <h2 class="mt-32">修改密码</h2>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
        ><el-form-item label="当前密码" prop="oldPassword"
          ><el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            autocomplete="current-password" /></el-form-item
        ><el-form-item label="新密码" prop="newPassword"
          ><el-input
            v-model="form.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="至少 8 位，建议包含字母和数字" /></el-form-item
        ><el-form-item label="确认新密码" prop="confirm"
          ><el-input
            v-model="form.confirm"
            type="password"
            show-password
            autocomplete="new-password" /></el-form-item
        ><el-button type="primary" :loading="busy" native-type="submit"
          >保存新密码</el-button
        ></el-form
      >
    </section>
  </div>
</template>
<style scoped>
.avatar-settings { display: flex; align-items: center; gap: 18px; margin: 0 0 24px; }
.avatar-controls { min-width: 0; }
.avatar-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.avatar-actions .el-button + .el-button { margin-left: 0; }
.avatar-file-input { display: none; }
.avatar-controls p { margin: 9px 0 0; color: #818791; font-size: 12px; line-height: 1.7; }
.avatar-status { display: flex; align-items: center; gap: 10px; margin-top: 6px; font-size: 12px; color: #8a7141; }
@media (max-width: 480px) {
  .avatar-settings { gap: 12px; align-items: flex-start; }
}
</style>
