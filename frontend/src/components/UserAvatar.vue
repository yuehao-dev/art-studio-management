<script setup>
import { User } from "@element-plus/icons-vue";
import { avatar, avatarImageFailed } from "../avatar";
defineProps({ size: { type: Number, default: 30 }, alt: { type: String, default: "账户头像" } });
function imageError(event) {
  avatarImageFailed(event.target.getAttribute("src"), Number(event.target.dataset.revision));
}
</script>
<template>
  <span class="user-avatar" :style="{ width: size + 'px', height: size + 'px' }">
    <img v-if="avatar.url" :key="avatar.revision" :src="avatar.url"
      :data-revision="avatar.revision" :alt="alt" @error="imageError" />
    <el-icon v-else :size="Math.round(size * 0.52)" role="img" aria-label="默认头像"><User /></el-icon>
  </span>
</template>
<style scoped>
.user-avatar {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid #dce1e7;
  border-radius: 50%;
  background: #f0f2f5;
  color: #8993a0;
  vertical-align: middle;
}
.user-avatar img { width: 100%; height: 100%; object-fit: cover; }
</style>
