<script setup>
import { onBeforeUnmount, ref, watch } from "vue";
import { Picture } from "@element-plus/icons-vue";
import { api, fileUrl } from "../api";

const props = defineProps({
  workId: { type: Number, required: true },
  title: String,
});
const url = ref("");
const state = ref("loading");
let request = 0;
watch(
  () => props.workId,
  async (id) => {
    const current = ++request;
    url.value = "";
    state.value = "loading";
    try {
      const versions = await api.get(`/works/${id}/versions`, undefined, {
        silent: true,
      });
      if (current !== request) return;
      const latest = [...versions].sort(
        (a, b) => Number(b.versionNo) - Number(a.versionNo),
      )[0];
      if (
        !latest?.fileId ||
        !String(latest.contentType || "").startsWith("image/")
      ) {
        if (current === request) state.value = "document";
        return;
      }
      const result = await fileUrl(latest.fileId, false, { silent: true });
      if (current === request) url.value = result;
    } catch {
      if (current === request) state.value = "error";
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++request;
});
</script>

<template>
  <div
    class="work-thumbnail"
    :class="{ 'is-loading': state === 'loading' && !url }"
  >
    <img
      v-if="url && state !== 'error'"
      :src="url"
      :alt="title"
      loading="lazy"
      @load="state = 'ready'"
      @error="state = 'error'"
    />
    <span v-else class="thumbnail-placeholder">
      <el-icon><Picture /></el-icon>
      <small>{{
        state === "loading"
          ? "加载作品"
          : state === "document"
            ? "作品附件"
            : "预览暂不可用"
      }}</small>
    </span>
  </div>
</template>

<style scoped>
.work-thumbnail {
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: #f1f3f2;
  display: grid;
  place-items: center;
}
.work-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.thumbnail-placeholder {
  display: grid;
  justify-items: center;
  gap: 8px;
  color: #768c91;
}
.thumbnail-placeholder .el-icon {
  font-size: 28px;
}
.thumbnail-placeholder small {
  font-size: 11px;
}
</style>
