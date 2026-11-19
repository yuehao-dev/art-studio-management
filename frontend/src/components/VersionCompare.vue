<script setup>
import { computed, ref, watch } from "vue";
const props = defineProps({
  versions: { type: Array, required: true },
  previews: { type: Object, required: true },
});
const emit = defineEmits(["retry", "loaded", "failed", "open"]);
const leftId = ref(),
  rightId = ref();
const ordered = computed(() =>
  [...props.versions].sort((a, b) => a.versionNo - b.versionNo),
);
watch(
  ordered,
  (items) => {
    leftId.value = items[0]?.id;
    rightId.value = items.at(-1)?.id;
  },
  { immediate: true },
);
const left = computed(() => ordered.value.find((v) => v.id === leftId.value));
const right = computed(() => ordered.value.find((v) => v.id === rightId.value));
const columns = computed(() => [left.value, right.value]);
</script>

<template>
  <div class="version-compare">
    <p class="compare-hint">
      选择同一作品的两个版本，对照画面、修改说明与教师点评。
    </p>
    <div class="compare-selectors">
      <label
        >修改前
        <el-select v-model="leftId" aria-label="修改前版本">
          <el-option
            v-for="v in ordered"
            :key="v.id"
            :label="`第 ${v.versionNo} 版`"
            :value="v.id"
            :disabled="v.versionNo >= (right?.versionNo || Infinity)"
          />
        </el-select>
      </label>
      <label
        >修改后
        <el-select v-model="rightId" aria-label="修改后版本">
          <el-option
            v-for="v in ordered"
            :key="v.id"
            :label="`第 ${v.versionNo} 版`"
            :value="v.id"
            :disabled="v.versionNo <= (left?.versionNo || 0)"
          />
        </el-select>
      </label>
    </div>
    <div class="compare-columns">
      <article
        v-for="(version, index) in columns"
        :key="index"
        class="compare-version"
      >
        <template v-if="version">
          <div class="compare-image">
            <img
              v-if="
                previews[version.id]?.url &&
                previews[version.id]?.status !== 'error'
              "
              :key="previews[version.id].attempt"
              :src="previews[version.id].url"
              :data-attempt="previews[version.id].attempt"
              :alt="`第 ${version.versionNo} 版作品对比`"
              @load="emit('loaded', version.id, $event)"
              @error="emit('failed', version.id, $event)"
            />
            <div v-else class="compare-placeholder">
              <span>{{
                previews[version.id]?.status === "loading"
                  ? "图片加载中…"
                  : "此版本暂无图片预览"
              }}</span>
              <el-button
                v-if="previews[version.id]?.status === 'error'"
                size="small"
                @click="emit('retry', version)"
                >重新加载</el-button
              >
              <el-button
                link
                type="primary"
                @click="emit('open', version.fileId)"
                >打开作品文件</el-button
              >
            </div>
          </div>
          <div class="compare-notes">
            <h3>
              第 {{ version.versionNo }} 版
              <small>{{
                String(version.createdAt || "").replace("T", " ")
              }}</small>
            </h3>
            <p class="pre-wrap">
              {{ version.description || "未填写修改说明" }}
            </p>
            <h4>教师点评</h4>
            <p v-if="!version.reviews?.length" class="muted">教师暂未点评</p>
            <div
              v-for="review in version.reviews || []"
              :key="review.id"
              class="compare-review"
            >
              <div>
                {{ review.teacherName || review.reviewerName || "任课教师"
                }}<span v-if="review.score != null">{{ review.score }} 分</span>
              </div>
              <p class="pre-wrap">{{ review.content }}</p>
            </div>
          </div>
        </template>
      </article>
    </div>
  </div>
</template>

<style scoped>
.compare-hint {
  margin: 0 0 18px;
  font-size: 12px;
  line-height: 1.8;
  color: #758594;
}
.compare-selectors,
.compare-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.compare-selectors {
  margin-bottom: 16px;
}
.compare-selectors label {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
  font-weight: 500;
  color: #365366;
}
.compare-selectors .el-select {
  flex: 1;
}
.compare-version {
  min-width: 0;
  border: 1px solid #e3e9eb;
  border-radius: 7px;
  overflow: hidden;
}
.compare-image {
  aspect-ratio: 4/3;
  background: #f2f3ef;
  display: grid;
  place-items: center;
}
.compare-image img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.compare-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 16px;
  font-size: 12px;
  color: #778a98;
}
.compare-notes {
  padding: 18px;
}
.compare-notes h3 {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 12px;
}
.compare-notes h3 small {
  font-size: 11px;
  font-weight: 400;
  color: #8192a0;
}
.compare-notes p {
  font-size: 13px;
  line-height: 1.9;
  color: #587082;
}
.compare-notes h4 {
  border-top: 1px solid #edf0f2;
  padding-top: 16px;
  margin-bottom: 10px;
}
.compare-review > div {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #526d7d;
}
.compare-review span {
  color: #a66b23;
}
@media (max-width: 640px) {
  .compare-columns {
    grid-template-columns: 1fr;
  }
  .compare-selectors {
    gap: 10px;
  }
  .compare-selectors label {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    font-size: 12px;
  }
}
</style>
