<script setup>
import { ref, onBeforeUnmount } from "vue";
import { ElMessage } from "element-plus";
import { Upload, Document, Check } from "@element-plus/icons-vue";
import { upload } from "../api";
import { optionLabel } from "../pages";
const props = defineProps({
  fields: Array,
  model: Object,
  options: Object,
  participants: Array,
  editing: Boolean,
});
const emit = defineEmits(["change", "uploading"]);
const uploading = ref(false),
  fileName = ref("");
let mounted = true;
onBeforeUnmount(() => { mounted = false; });
function choices(field) {
  if (field.type === "participant")
    return (props.participants || [])
      .filter((x) => x.status !== "CANCELLED")
      .map((x) => ({
        label: `${x.studentName || "学员 " + x.studentId}（${{ PRESENT: "已出勤", ABSENT: "缺勤", LEAVE: "请假" }[x.attendanceStatus] || "未登记"}）`,
        value: x.id,
      }));
  if (field.options) return field.options;
  return (props.options?.[field.source] || []).map((row) => ({
    label: optionLabel(row, field.source),
    value: row.id,
  }));
}
async function handleFile(event, field) {
  const file = event.target.files?.[0];
  if (!file) return;
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning("文件不能超过 10 MB");
    event.target.value = "";
    return;
  }
  if (
    !["image/jpeg", "image/png", "image/webp", "application/pdf"].includes(
      file.type,
    )
  ) {
    ElMessage.warning("请选择 JPG、PNG、WebP 图片或 PDF 文件");
    event.target.value = "";
    return;
  }
  uploading.value = true;
  emit("uploading", true);
  try {
    const uploaded = await upload(file, field.purpose || "attachments");
    if (!mounted) return;
    props.model[field.key] = uploaded.id;
    fileName.value = uploaded.originalName || file.name;
    emit("change", field);
  } catch {
  } finally {
    uploading.value = false;
    if (mounted) emit("uploading", false);
    event.target.value = "";
  }
}
</script>
<template>
  <template v-for="field in fields" :key="field.key"
    ><el-form-item
      v-if="!field.editOnly || editing"
      :label="field.label"
      :prop="field.key"
      ><el-input
        v-if="['text', 'password', 'textarea'].includes(field.type)"
        v-model="model[field.key]"
        :type="field.type"
        :show-password="field.type === 'password'"
        :rows="field.type === 'textarea' ? 4 : undefined"
        :maxlength="field.type === 'textarea' ? 2000 : 200"
        :show-word-limit="field.type === 'textarea'"
        :placeholder="field.placeholder || '请输入' + field.label"
        @change="emit('change', field)"
      /><el-select
        v-else-if="['select', 'participant'].includes(field.type)"
        v-model="model[field.key]"
        filterable
        clearable
        :disabled="editing && field.immutableOnEdit"
        :placeholder="field.placeholder || '请选择' + field.label"
        class="full-width"
        @change="emit('change', field)"
        ><el-option
          v-for="option in choices(field)"
          :key="option.value"
          :label="option.label"
          :value="option.value" /></el-select
      ><el-input-number
        v-else-if="field.type === 'number'"
        v-model="model[field.key]"
        :min="field.min ?? 0"
        :max="field.max ?? 999999"
        :precision="0"
        controls-position="right"
        class="full-width"
      /><el-date-picker
        v-else-if="field.type === 'date'"
        v-model="model[field.key]"
        type="date"
        value-format="YYYY-MM-DD"
        :placeholder="field.placeholder || '选择日期'"
        class="full-width"
      /><el-date-picker
        v-else-if="field.type === 'datetime'"
        v-model="model[field.key]"
        type="datetime"
        value-format="YYYY-MM-DDTHH:mm:ss"
        format="YYYY-MM-DD HH:mm"
        placeholder="选择日期与时间"
        class="full-width"
      /><el-switch
        v-else-if="field.type === 'switch'"
        v-model="model[field.key]"
      />
      <div v-else-if="field.type === 'file'" class="file-field">
        <label class="file-select" :class="{ busy: uploading }"
          ><el-icon><Upload /></el-icon
          >{{
            uploading
              ? "正在上传…"
              : model[field.key]
                ? "重新选择文件"
                : "选择文件"
          }}<input
            type="file"
            accept=".jpg,.jpeg,.png,.webp,.pdf"
            :disabled="uploading"
            @change="handleFile($event, field)" /></label
        ><span v-if="model[field.key]" class="file-selected"
          ><el-icon><Check /></el-icon>{{ fileName || "文件已上传" }}</span
        ><small>支持 JPG、PNG、WebP、PDF，单个文件不超过 10 MB。</small>
      </div></el-form-item
    ></template
  >
</template>
