<script setup>
import {
  computed,
  ref,
  reactive,
  onMounted,
  onBeforeUnmount,
  watch,
  nextTick,
} from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Plus,
  Search,
  Refresh,
  Document,
  ArrowRight,
  ArrowDown,
} from "@element-plus/icons-vue";
import { api, fileUrl } from "../api";
import { auth } from "../auth";
import { pages, translate, dateText, optionLabel, f, select } from "../pages";
import Fields from "../components/Fields.vue";
import VersionCompare from "../components/VersionCompare.vue";
import WorkThumbnail from "../components/WorkThumbnail.vue";

const props = defineProps({ resource: String });
const compactViewport = window.matchMedia("(max-width: 540px)");
const narrowScreen = ref(compactViewport.matches);
function syncViewport(event) {
  narrowScreen.value = event.matches;
}
const config = computed(() => pages[props.resource]),
  rows = ref([]),
  loading = ref(false),
  failed = ref(false),
  search = ref(""),
  page = ref(1),
  pageSize = ref(10),
  statusFilter = ref("");
const options = reactive({}),
  participants = ref([]),
  participantsLoading = ref(false),
  form = reactive({}),
  dialog = ref(false),
  dialogTitle = ref(""),
  dialogFields = ref([]),
  formRef = ref(),
  saving = ref(false),
  uploading = ref(false),
  editing = ref(false),
  action = ref(null),
  editingRow = ref(null);
const attendanceVisible = ref(false),
  attendanceRows = ref([]),
  attendanceLesson = ref(null),
  attendanceReason = ref(""),
  attendanceLoading = ref(false);
const workVisible = ref(false),
  workView = ref("history"),
  currentWork = ref(null),
  versions = ref([]),
  versionsLoading = ref(false),
  versionPreviews = reactive({});
let workRequest = 0,
  previewRequest = 0,
  listRequest = 0,
  participantsRequest = 0,
  attendanceRequest = 0;
const noticeVisible = ref(false),
  currentNotice = ref(null);
const progressFilters = reactive({ classId: null, studentId: null, dates: [] });
const writable = computed(() => !auth.user?.readOnly),
  isAdmin = computed(() => auth.user?.role === "ADMIN"),
  isStaff = computed(() => ["ADMIN", "TEACHER"].includes(auth.user?.role));
const can = (roles) => writable.value && roles?.includes(auth.user?.role);
const createLabel = computed(
  () =>
    ({
      students: "新增学员",
      teachers: "新增教师",
      courses: "新增课程",
      classes: "创建班级",
      rooms: "新增教室",
      enrollments: "办理报名",
      leaves: "申请请假",
      works: "提交作品",
      feedback: "提交反馈",
      notices: "发布通知",
      resources: "上传资料",
      tasks: "发布任务",
      users: "创建账号",
      lessons: "新增课次",
      makeups: "安排补课",
      "makeup-suggestions": "提出建议",
    })[props.resource] || "新增",
);
const filtered = computed(() =>
  rows.value.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false;
    if (!search.value.trim()) return true;
    return config.value.columns.some((col) =>
      String(format(row, col))
        .toLowerCase()
        .includes(search.value.trim().toLowerCase()),
    );
  }),
);
const paged = computed(() =>
  filtered.value.slice(
    (page.value - 1) * pageSize.value,
    page.value * pageSize.value,
  ),
);
const statuses = computed(() => [
  ...new Set(rows.value.map((r) => r.status).filter(Boolean)),
]);
const tableColumns = computed(() =>
  props.resource === "lessons"
    ? [
        { key: "_lesson", label: "课程安排", width: 240 },
        { key: "_time", label: "上课时间", width: 190 },
        { key: "_teacherRoom", label: "教师 / 教室", width: 145 },
        { key: "status", label: "状态", enum: true },
      ]
    : config.value.columns,
);
const formRules = computed(() => {
  const rules = {};
  for (const field of dialogFields.value) {
    if (field.editOnly && !editing.value) continue;
    if (
      field.required ||
      (field.key === "reason" && props.resource === "lessons" && editing.value)
    )
      rules[field.key] = [
        {
          required: true,
          message: `请${["select", "participant", "date", "datetime", "file"].includes(field.type) ? "选择" : "填写"}${field.label}`,
          trigger: "change",
        },
      ];
  }
  return rules;
});
const formOptions = computed(() => {
  const result = { ...options };
  if (props.resource === "works")
    result.tasks = (options.tasks || []).filter(
      (x) => x.submissionAllowed !== false,
    );
  if (props.resource === "leaves")
    result.lessons = (options.lessons || []).filter(
      (x) =>
        x.status === "SCHEDULED" &&
        new Date(x.startTime).getTime() > Date.now(),
    );
  if (props.resource === "tasks")
    result.lessons = (options.lessons || []).filter(
      (x) => x.status !== "CANCELLED" && x.classId === form.classId,
    );
  if (["enrollments", "tasks", "lessons"].includes(props.resource))
    result.classes = (options.classes || []).filter(
      (x) => x.status === "ACTIVE",
    );
  return result;
});
function valueLabel(id, source) {
  const row = (options[source] || []).find((x) => Number(x.id) === Number(id));
  return row ? optionLabel(row, source) : null;
}
function format(row, col) {
  const value = row[col.key];
  if (col.source) {
    const direct =
      row[col.key.replace(/Id$/, "Name")] ||
      row[col.key.replace(/Id$/, "Title")] ||
      row[col.fallback];
    return (
      direct ||
      valueLabel(value, col.source) ||
      (value == null ? col.empty || "—" : String(value))
    );
  }
  if (col.enum) return translate(value);
  if (col.date) return dateText(value);
  if (col.bool) return value ? "是" : "否";
  if (col.read) return value ? "已读" : "未读";
  if (col.percent) {
    if (value == null) return "—";
    return `${Number(value).toFixed(1)}%`;
  }
  return value ?? row[col.fallback] ?? "—";
}
function tone(value) {
  return ["CANCELLED", "REJECTED", "ABSENT", "CLOSED"].includes(value)
    ? "muted-state"
    : ["PENDING", "OPEN", "LEAVE"].includes(value)
      ? "pending-state"
      : "state-text";
}
function rowActions(row) {
  const items = [];
  const add = (key, label, run, danger = false) =>
    items.push({ key, label, run, danger });
  const resource = props.resource;
  if (resource === "lessons")
    add("attendance", isStaff.value ? "名单 / 考勤" : "出勤记录", () =>
      openAttendance(row),
    );
  if (resource === "works") add("work", "版本与点评", () => showWork(row));
  if (resource === "resources")
    add("file", "查看文件", () => openFile(row.fileId));
  if (resource === "notices") add("notice", "查看通知", () => showNotice(row));
  if (resource === "feedback" && isAdmin.value)
    add("reply", "回复", () => replyFeedback(row));
  if (
    can(config.value.editRoles) &&
    (resource !== "lessons" || row.status === "SCHEDULED")
  )
    add("edit", resource === "lessons" ? "调整" : "编辑", () => openEdit(row));
  if (resource === "classes" && isAdmin.value && row.status !== "CLOSED")
    add("close", "结班", () => closeClass(row));
  if (resource === "enrollments" && isAdmin.value && row.status === "ACTIVE")
    add("transfer", "转班", () => transfer(row));
  if (
    resource === "leaves" &&
    isStaff.value &&
    writable.value &&
    row.status === "PENDING"
  ) {
    add("approve", "通过", () => reviewLeave(row, true));
    add("reject", "驳回", () => reviewLeave(row, false), true);
  }
  if (resource === "lessons" && isAdmin.value && row.status === "SCHEDULED")
    add("cancel", "停课", () => cancelLesson(row), true);
  if (
    resource === "makeups" &&
    isAdmin.value &&
    !["COMPLETED", "CANCELLED"].includes(row.status)
  )
    add("cancel", "取消安排", () => cancelMakeup(row), true);
  if (can(config.value.deleteRoles))
    add("delete", "删除", () => remove(row), true);
  return items;
}
const showRowActions = computed(() =>
  rows.value.some((row) => rowActions(row).length > 0),
);
function columnWidth(col) {
  if (props.resource === "works" && col.key === "title") return 270;
  if (col.width) return col.width;
  if (col.enum || col.bool || col.read) return 96;
  if (col.percent) return 110;
  if (col.date) return 165;
  if (["description", "content", "detail"].includes(col.key)) return 240;
  if (["phone", "guardianPhone"].includes(col.key)) return 150;
  if (col.key === "birthday") return 128;
  if (["title", "name"].includes(col.key)) return 170;
  return 130;
}
async function load() {
  const request = ++listRequest;
  loading.value = true;
  failed.value = false;
  try {
    const params =
      props.resource === "progress"
        ? {
            classId: progressFilters.classId || undefined,
            studentId: progressFilters.studentId || undefined,
            from: progressFilters.dates?.[0],
            to: progressFilters.dates?.[1],
          }
        : undefined;
    const result = (await api.get(`/${props.resource}`, params)) || [];
    if (request !== listRequest) return;
    rows.value = result;
    if (!Array.isArray(rows.value))
      rows.value = rows.value.records || rows.value.items || [];
    if (
      page.value >
      Math.max(1, Math.ceil(filtered.value.length / pageSize.value))
    )
      page.value = 1;
  } catch {
    if (request === listRequest) failed.value = true;
  } finally {
    if (request === listRequest) loading.value = false;
  }
}
async function loadOptions() {
  const needed = new Set(
    [...config.value.columns, ...(config.value.fields || [])]
      .map((x) => x.source)
      .filter(Boolean),
  );
  if (
    ["lessons", "enrollments", "makeups", "makeup-suggestions"].includes(
      props.resource,
    )
  )
    needed.add("lessons");
  if (props.resource === "works") needed.add("tasks");
  if (props.resource === "lessons") {
    needed.add("classes");
    needed.add("teachers");
    needed.add("rooms");
  }
  if (props.resource === "classes") {
    needed.add("teachers");
    needed.add("courses");
  }
  await Promise.allSettled(
    [...needed].map(async (key) => {
      if (key === props.resource) {
        options[key] = rows.value;
        return;
      }
      options[key] = await api.get(`/${key}`);
    }),
  );
}
async function refresh() {
  await load();
  await loadOptions();
  if (props.resource === "progress")
    options.classes = await api.get("/classes");
}
function cleanModel() {
  ++participantsRequest;
  participantsLoading.value = false;
  for (const key of Object.keys(form)) delete form[key];
  participants.value = [];
  uploading.value = false;
}
async function openCreate() {
  cleanModel();
  editing.value = false;
  editingRow.value = null;
  dialogTitle.value = createLabel.value;
  dialogFields.value = config.value.fields || [];
  for (const field of dialogFields.value)
    form[field.key] = field.default ?? null;
  action.value = { method: "post", url: `/${props.resource}` };
  dialog.value = true;
  await nextTick();
  formRef.value?.clearValidate();
}
async function openEdit(row) {
  cleanModel();
  editing.value = true;
  editingRow.value = row;
  dialogTitle.value =
    props.resource === "lessons" ? "调整课次" : `编辑${config.value.title}`;
  dialogFields.value = config.value.fields;
  for (const field of dialogFields.value) {
    let value = row[field.key] ?? field.default ?? null;
    if (field.type === "switch") value = Boolean(value);
    if (field.key === "password" || field.key === "reason") value = null;
    form[field.key] = value;
  }
  action.value = { method: "put", url: `/${props.resource}/${row.id}` };
  dialog.value = true;
  await nextTick();
  formRef.value?.clearValidate();
}
async function openAction(title, fields, url, method = "post", initial = {}) {
  cleanModel();
  editing.value = false;
  editingRow.value = null;
  dialogTitle.value = title;
  dialogFields.value = fields;
  for (const field of fields)
    form[field.key] = initial[field.key] ?? field.default ?? null;
  action.value = { method, url };
  dialog.value = true;
  await nextTick();
  formRef.value?.clearValidate();
}
async function changed(field) {
  if (field.key === "_originalLessonId") {
    const request = ++participantsRequest;
    form.originalParticipantId = null;
    participants.value = [];
    participantsLoading.value = false;
    if (form._originalLessonId) {
      participantsLoading.value = true;
      try {
        const result = await api.get(
          `/lessons/${form._originalLessonId}/participants`,
        );
        if (request === participantsRequest) participants.value = result;
      } catch {
      } finally {
        if (request === participantsRequest) participantsLoading.value = false;
      }
    }
  }
  if (field.key === "classId" && props.resource === "tasks")
    form.lessonId = null;
  if (
    field.key === "classId" &&
    props.resource === "lessons" &&
    !editing.value
  ) {
    const cls = options.classes?.find((x) => x.id === form.classId);
    if (cls) form.teacherId = cls.teacherId;
  }
}
async function submit() {
  if (uploading.value || participantsLoading.value || saving.value) return;
  if (!(await formRef.value.validate().catch(() => false))) return;
  const payload = {};
  for (const field of dialogFields.value) {
    if (field.key.startsWith("_") || (field.editOnly && !editing.value))
      continue;
    let value = form[field.key];
    if (value === "") value = null;
    if (field.key === "password" && !value) continue;
    payload[field.key] = value;
  }
  if (
    props.resource === "users" &&
    action.value.url === "/users" &&
    !payload.password
  ) {
    ElMessage.warning("新增账户请填写密码");
    return;
  }
  if (payload.password && payload.password.length < 8) {
    ElMessage.warning("密码至少为 8 位");
    return;
  }
  if (
    payload.startTime &&
    payload.endTime &&
    payload.startTime >= payload.endTime
  ) {
    ElMessage.warning("结束时间必须晚于开始时间");
    return;
  }
  saving.value = true;
  try {
    await api[action.value.method](action.value.url, payload);
    ElMessage.success("保存成功");
    dialog.value = false;
    await refresh();
    if (workVisible.value && currentWork.value) await loadVersions();
  } catch {
  } finally {
    saving.value = false;
  }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除“${row.name || row.title || "此记录"}”吗？有业务关联的记录不能删除。`,
      "删除确认",
      {
        confirmButtonText: "确认删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    await api.delete(`/${props.resource}/${row.id}`);
    ElMessage.success("已删除");
    await refresh();
  } catch {}
}
async function closeClass(row) {
  try {
    await ElMessageBox.confirm(
      "结班后将停止新增报名，历史教学记录会保留。确定继续吗？",
      "结班确认",
      {
        confirmButtonText: "确认结班",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    await api.post(`/classes/${row.id}/close`);
    ElMessage.success("已结班");
    await refresh();
  } catch {}
}
function transfer(row) {
  openAction(
    "办理转班",
    [
      select("classId", "转入班级", "classes"),
      f("effectiveDate", "生效日期", "date"),
      f("reason", "转班原因", "textarea"),
    ],
    `/enrollments/${row.id}/transfer`,
  );
}
function cancelLesson(row) {
  openAction(
    "取消课次",
    [f("reason", "取消原因", "textarea")],
    `/lessons/${row.id}/cancel`,
  );
}
function reviewLeave(row, approved) {
  openAction(
    approved ? "通过请假申请" : "驳回请假申请",
    [
      f("approved", "审批结果", "select", {
        default: approved,
        options: [
          { label: "通过", value: true },
          { label: "驳回", value: false },
        ],
      }),
      f("comment", "处理意见", "textarea", { required: !approved }),
    ],
    `/leaves/${row.id}/review`,
  );
}
function cancelMakeup(row) {
  openAction(
    "取消补课安排",
    [f("reason", "取消原因", "textarea")],
    `/makeups/${row.id}/cancel`,
  );
}
function replyFeedback(row) {
  openAction(
    "回复意见反馈",
    [f("reply", "回复内容", "textarea")],
    `/feedback/${row.id}/reply`,
    "put",
    { reply: row.reply },
  );
}
async function openAttendance(row) {
  const request = ++attendanceRequest;
  attendanceRows.value = [];
  attendanceLesson.value = row;
  attendanceVisible.value = true;
  attendanceLoading.value = true;
  attendanceReason.value = "";
  try {
    const result = await api.get(`/lessons/${row.id}/participants`);
    if (request !== attendanceRequest || !attendanceVisible.value) return;
    attendanceRows.value = result.map((x) => ({
      ...x,
      selectedStatus: x.attendanceStatus || "PRESENT",
      remark: x.remark || "",
      originalRemark: x.remark || "",
    }));
  } catch {
  } finally {
    if (request === attendanceRequest) attendanceLoading.value = false;
  }
}
const attendanceWritable = computed(
  () =>
    isStaff.value &&
    writable.value &&
    attendanceLesson.value?.status !== "CANCELLED" &&
    new Date(attendanceLesson.value?.startTime).getTime() <= Date.now(),
);
async function saveAttendance() {
  if (
    !attendanceRows.value.length ||
    attendanceLoading.value ||
    !attendanceWritable.value
  )
    return;
  const existing = attendanceRows.value.some(
    (x) =>
      x.attendanceStatus &&
      (x.attendanceStatus !== x.selectedStatus ||
        x.remark !== x.originalRemark),
  );
  if (existing && !attendanceReason.value.trim()) {
    ElMessage.warning("更正已有考勤时，请填写更正原因");
    return;
  }
  attendanceLoading.value = true;
  try {
    await api.post(`/lessons/${attendanceLesson.value.id}/attendance`, {
      records: attendanceRows.value
        .filter((x) => x.status !== "CANCELLED")
        .map((x) => ({
          participantId: x.id,
          status: x.selectedStatus,
          remark: x.remark || "",
        })),
      reason: attendanceReason.value,
    });
    ElMessage.success("考勤已保存");
    attendanceVisible.value = false;
    await refresh();
  } catch {
  } finally {
    attendanceLoading.value = false;
  }
}
async function openFile(id) {
  const win = window.open("about:blank", "_blank");
  if (win) win.opener = null;
  try {
    const url = await fileUrl(id, true);
    if (win) win.location.href = url;
    else window.location.assign(url);
  } catch {
    win?.close();
  }
}
function isImageVersion(version) {
  return (
    version.fileId &&
    !String(version.contentType || version.originalName || "")
      .toLowerCase()
      .includes("pdf")
  );
}
function clearVersionPreviews() {
  for (const key of Object.keys(versionPreviews)) delete versionPreviews[key];
}
async function loadPreview(version, request = workRequest) {
  if (!workVisible.value || request !== workRequest) return;
  const preview = reactive({
    url: "",
    status: "loading",
    attempt: ++previewRequest,
  });
  versionPreviews[version.id] = preview;
  try {
    const url = await fileUrl(version.fileId);
    if (
      workVisible.value &&
      request === workRequest &&
      versionPreviews[version.id] === preview
    )
      preview.url = url;
  } catch {
    if (
      workVisible.value &&
      request === workRequest &&
      versionPreviews[version.id] === preview
    )
      preview.status = "error";
  }
}
function finishPreview(id, event, status) {
  const preview = versionPreviews[id];
  if (
    workVisible.value &&
    preview &&
    String(preview.attempt) === event.currentTarget.dataset.attempt
  )
    preview.status = status;
}
async function loadVersions() {
  const request = ++workRequest,
    workId = currentWork.value.id;
  versionsLoading.value = true;
  versions.value = [];
  clearVersionPreviews();
  try {
    const result = await api.get(`/works/${workId}/versions`);
    if (!workVisible.value || request !== workRequest) return;
    versions.value = result;
    await Promise.allSettled(
      result
        .filter(isImageVersion)
        .map((version) => loadPreview(version, request)),
    );
  } catch {
  } finally {
    if (request === workRequest) versionsLoading.value = false;
  }
}
async function showWork(row) {
  currentWork.value = row;
  workView.value = "history";
  workVisible.value = true;
  await loadVersions();
}
function addVersion() {
  openAction(
    "提交新的作品版本",
    [
      f("fileId", "作品文件", "file", { purpose: "works" }),
      f("description", "本次修改说明", "textarea"),
    ],
    `/works/${currentWork.value.id}/versions`,
  );
}
function addReview(version) {
  openAction(
    `点评第 ${version.versionNo} 版作品`,
    [
      f("versionId", "版本", "select", {
        default: version.id,
        options: [{ label: `第 ${version.versionNo} 版`, value: version.id }],
      }),
      f("score", "评分（0–100）", "number", {
        required: false,
        min: 0,
        max: 100,
      }),
      f("content", "点评与修改建议", "textarea"),
    ],
    `/works/${currentWork.value.id}/reviews`,
  );
}
async function showNotice(row) {
  currentNotice.value = row;
  noticeVisible.value = true;
  try {
    await api.post(`/notices/${row.id}/read`);
    row.read = true;
  } catch {}
}
watch([search, statusFilter, pageSize], () => (page.value = 1));
watch(
  workVisible,
  (visible) => {
    if (!visible) {
      ++workRequest;
      versionsLoading.value = false;
      clearVersionPreviews();
    }
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  compactViewport.removeEventListener("change", syncViewport);
  ++workRequest;
  ++listRequest;
  ++participantsRequest;
  ++attendanceRequest;
});
onMounted(() => {
  compactViewport.addEventListener("change", syncViewport);
  refresh();
});
</script>

<template>
  <div>
    <div class="page-heading">
      <div>
        <h1>{{ config.title }}</h1>
        <p>{{ config.description }}</p>
      </div>
      <el-button
        v-if="can(config.createRoles)"
        type="primary"
        :icon="Plus"
        @click="openCreate"
        >{{ createLabel }}</el-button
      >
    </div>
    <section class="panel data-panel">
      <div
        v-if="resource === 'progress'"
        class="table-toolbar progress-filters"
      >
        <el-select
          v-model="progressFilters.classId"
          clearable
          filterable
          placeholder="全部班级"
          @change="load"
          ><el-option
            v-for="item in options.classes || []"
            :key="item.id"
            :value="item.id"
            :label="item.name" /></el-select
        ><el-select
          v-if="isStaff"
          v-model="progressFilters.studentId"
          clearable
          filterable
          placeholder="全部学员"
          @change="load"
          ><el-option
            v-for="item in options.students || []"
            :key="item.id"
            :value="item.id"
            :label="item.name" /></el-select
        ><el-date-picker
          v-model="progressFilters.dates"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          range-separator="至"
          @change="load"
        />
      </div>
      <div class="table-toolbar">
        <div class="table-filters">
          <el-input
            v-model="search"
            :prefix-icon="Search"
            clearable
            :placeholder="
              resource === 'audit-logs'
                ? '搜索操作人、操作或记录内容'
                : '输入关键词筛选'
            "
            class="search-input"
            :aria-label="'搜索' + config.title"
          /><el-select
            v-if="statuses.length"
            v-model="statusFilter"
            clearable
            placeholder="全部状态"
            class="status-filter"
            ><el-option
              v-for="status in statuses"
              :key="status"
              :label="translate(status)"
              :value="status"
          /></el-select>
        </div>
        <div class="toolbar-right">
          <el-button
            v-if="search || statusFilter"
            link
            @click="
              search = '';
              statusFilter = '';
            "
            >重置筛选</el-button
          >
          <span class="record-count">共 {{ filtered.length }} 条</span
          ><el-button :icon="Refresh" aria-label="刷新列表" @click="refresh" />
        </div>
      </div>
      <el-alert
        v-if="failed"
        title="列表加载失败，请点击刷新重试。"
        type="error"
        :closable="false"
        class="mb-20"
      />
      <p class="mobile-scroll-hint">左右滑动表格查看完整信息</p>
      <el-table
        v-loading="loading"
        :data="paged"
        row-key="id"
        :empty-text="search || statusFilter ? '没有匹配的记录' : '暂无记录'"
        class="resource-table"
      >
        <el-table-column
          v-for="col in tableColumns"
          :key="col.key"
          :label="col.label"
          :min-width="columnWidth(col)"
          show-overflow-tooltip
          ><template #default="{ row }"
            ><button
              v-if="resource === 'works' && col.key === 'title'"
              class="work-title-cell"
              @click="showWork(row)"
            >
              <WorkThumbnail
                :key="`${row.id}:${row.latestFileId}`"
                :work-id="row.id"
                :title="row.title"
              />
              <span>{{ row.title }}</span>
            </button>
            <div v-else-if="col.key === '_lesson'" class="table-stack">
              <strong>{{ row.title }}</strong>
              <small>{{
                row.className || valueLabel(row.classId, "classes")
              }}</small>
            </div>
            <div v-else-if="col.key === '_time'" class="table-stack">
              <span>{{ dateText(row.startTime).slice(0, 10) }}</span>
              <small
                >{{ dateText(row.startTime).slice(11) }} —
                {{
                  row.endTime?.slice(0, 10) === row.startTime?.slice(0, 10)
                    ? dateText(row.endTime).slice(11)
                    : dateText(row.endTime)
                }}</small
              >
            </div>
            <div v-else-if="col.key === '_teacherRoom'" class="table-stack">
              <span>{{
                row.teacherName || valueLabel(row.teacherId, "teachers")
              }}</span>
              <small>{{
                row.roomName || valueLabel(row.roomId, "rooms")
              }}</small>
            </div>
            <el-tag
              v-else-if="
                (col.enum && col.key !== 'gender') || col.bool || col.read
              "
              class="status-tag"
              :class="col.key === 'status' ? tone(row[col.key]) : ''"
              effect="light"
              disable-transitions
              >{{ format(row, col) }}</el-tag
            ><span v-else>{{ format(row, col) }}</span></template
          >
        </el-table-column>
        <el-table-column
          v-if="showRowActions"
          label="操作"
          :width="resource === 'lessons' ? 185 : 170"
          :fixed="narrowScreen ? false : 'right'"
          ><template #default="{ row }">
            <div v-if="rowActions(row).length" class="row-actions">
              <el-button
                link
                :type="rowActions(row)[0].danger ? 'danger' : 'primary'"
                @click="rowActions(row)[0].run()"
                >{{ rowActions(row)[0].label }}</el-button
              >
              <el-dropdown
                v-if="rowActions(row).length > 1"
                trigger="click"
                @command="
                  (key) =>
                    rowActions(row)
                      .find((item) => item.key === key)
                      ?.run()
                "
              >
                <el-button
                  link
                  class="more-actions"
                  :aria-label="'更多操作：' + (row.name || row.title || row.id)"
                  >更多<el-icon><ArrowDown /></el-icon
                ></el-button>
                <template #dropdown
                  ><el-dropdown-menu
                    ><el-dropdown-item
                      v-for="item in rowActions(row).slice(1)"
                      :key="item.key"
                      :command="item.key"
                      :class="{ 'danger-action': item.danger }"
                      >{{ item.label }}</el-dropdown-item
                    ></el-dropdown-menu
                  ></template
                >
              </el-dropdown>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <span
          >显示 {{ filtered.length ? (page - 1) * pageSize + 1 : 0 }}–{{
            Math.min(page * pageSize, filtered.length)
          }}
          条</span
        ><el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="filtered.length"
          layout="sizes, prev, pager, next"
          background
        />
      </div>
    </section>

    <el-dialog
      v-model="dialog"
      :title="dialogTitle"
      :width="dialogFields.length > 4 ? '760px' : '540px'"
      :close-on-click-modal="false"
      :close-on-press-escape="!uploading && !saving"
      :show-close="!uploading && !saving"
      destroy-on-close
      append-to-body
      class="responsive-dialog"
      ><el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        class="entry-form"
        :class="{ 'entry-form-grid': dialogFields.length > 4 }"
        label-position="top"
        @submit.prevent="submit"
        ><Fields
          :fields="dialogFields"
          :model="form"
          :options="formOptions"
          :participants="participants"
          :editing="editing"
          @change="changed"
          @uploading="(v) => (uploading = v)" /></el-form
      ><template #footer
        ><el-button :disabled="saving || uploading" @click="dialog = false"
          >取消</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="uploading || participantsLoading"
          @click="submit"
          >{{ uploading ? "上传中…" : "保存" }}</el-button
        ></template
      ></el-dialog
    >

    <el-dialog
      v-model="attendanceVisible"
      :title="`${attendanceLesson?.title || '课次'} · ${isStaff ? '名单与考勤' : '出勤记录'}`"
      width="840px"
      :close-on-click-modal="false"
      class="responsive-dialog"
      ><div v-loading="attendanceLoading">
        <p class="dialog-subtitle">
          {{ dateText(attendanceLesson?.startTime) }} —
          {{ dateText(attendanceLesson?.endTime) }}<br />{{
            attendanceWritable
              ? "课次开始后可登记出勤，更正已保存的记录时需填写原因。"
              : "查看本课次的出勤记录。"
          }}
        </p>
        <el-table
          :data="attendanceRows.filter((x) => x.status !== 'CANCELLED')"
          empty-text="本课次暂无学员"
          ><el-table-column
            prop="studentName"
            label="学员"
            min-width="110"
          /><el-table-column label="类型" width="110"
            ><template #default="{ row }">{{
              translate(row.kind)
            }}</template></el-table-column
          ><el-table-column label="出勤状态" min-width="150"
            ><template #default="{ row }"
              ><el-select v-if="attendanceWritable" v-model="row.selectedStatus"
                ><el-option label="出勤" value="PRESENT" /><el-option
                  label="缺勤"
                  value="ABSENT" /><el-option
                  label="请假"
                  value="LEAVE" /></el-select
              ><span v-else>{{
                row.attendanceStatus
                  ? translate(row.attendanceStatus)
                  : "未登记"
              }}</span></template
            ></el-table-column
          ><el-table-column label="备注" min-width="180"
            ><template #default="{ row }"
              ><el-input
                v-if="attendanceWritable"
                v-model="row.remark"
                maxlength="500"
                placeholder="可选"
              /><span v-else>{{ row.remark || "—" }}</span></template
            ></el-table-column
          ></el-table
        ><el-form v-if="attendanceWritable" label-position="top" class="mt-24"
          ><el-form-item label="登记说明 / 更正原因"
            ><el-input
              v-model="attendanceReason"
              type="textarea"
              :rows="2"
              maxlength="1000"
              placeholder="更正已有出勤记录时必填" /></el-form-item
        ></el-form>
      </div>
      <template #footer
        ><el-button @click="attendanceVisible = false">关闭</el-button
        ><el-button
          v-if="attendanceWritable"
          type="primary"
          :loading="attendanceLoading"
          :disabled="!attendanceRows.length"
          @click="saveAttendance"
          >保存考勤</el-button
        ></template
      ></el-dialog
    >

    <el-drawer
      v-model="workVisible"
      :title="currentWork?.title || '作品档案'"
      :size="workView === 'compare' ? '1080px' : '760px'"
      class="work-drawer"
      ><div v-loading="versionsLoading">
        <div class="work-summary">
          <div>
            <p class="muted">
              {{
                currentWork?.studentName ||
                valueLabel(currentWork?.studentId, "students")
              }}
              ·
              {{
                currentWork?.taskTitle ||
                valueLabel(currentWork?.taskId, "tasks")
              }}
            </p>
            <p>共 {{ versions.length }} 个作品版本</p>
          </div>
          <el-button
            v-if="
              auth.user?.role === 'STUDENT' &&
              writable &&
              currentWork?.submissionAllowed !== false
            "
            type="primary"
            :icon="Plus"
            @click="addVersion"
            >提交新版本</el-button
          >
        </div>
        <el-empty
          v-if="!versions.length && !versionsLoading"
          description="暂时没有作品版本"
        />
        <el-radio-group
          v-if="versions.length > 1"
          v-model="workView"
          class="work-view-toggle"
          aria-label="作品查看方式"
        >
          <el-radio-button value="history">版本记录</el-radio-button>
          <el-radio-button value="compare">修改前后对比</el-radio-button>
        </el-radio-group>
        <VersionCompare
          v-if="workView === 'compare' && versions.length > 1"
          :versions="versions"
          :previews="versionPreviews"
          @retry="loadPreview"
          @loaded="(id, event) => finishPreview(id, event, 'ready')"
          @failed="(id, event) => finishPreview(id, event, 'error')"
          @open="openFile"
        />
        <template v-else>
          <article
            v-for="version in versions"
            :key="version.id"
            class="version-item"
          >
            <div class="version-heading">
              <h3>
                第 {{ version.versionNo }} 版
                <el-tag
                  v-if="
                    version.versionNo ===
                    Math.max(...versions.map((v) => v.versionNo))
                  "
                  size="small"
                  type="success"
                  >最新版本</el-tag
                >
              </h3>
              <span>{{ dateText(version.createdAt) }}</span>
            </div>
            <div class="version-content">
              <div v-if="isImageVersion(version)" class="work-preview">
                <div
                  v-if="versionPreviews[version.id]?.status === 'loading'"
                  class="work-preview-message"
                  role="status"
                >
                  图片加载中…
                </div>
                <div
                  v-else-if="versionPreviews[version.id]?.status === 'error'"
                  class="work-preview-message"
                  role="status"
                >
                  <span>图片加载失败，请重新加载或打开作品文件。</span>
                  <el-button
                    size="small"
                    :icon="Refresh"
                    @click="loadPreview(version)"
                    >重新加载</el-button
                  >
                </div>
                <img
                  v-if="
                    versionPreviews[version.id]?.url &&
                    versionPreviews[version.id]?.status !== 'error'
                  "
                  v-show="versionPreviews[version.id]?.status === 'ready'"
                  :key="versionPreviews[version.id].attempt"
                  :src="versionPreviews[version.id].url"
                  :data-attempt="versionPreviews[version.id].attempt"
                  :alt="`第 ${version.versionNo} 版作品`"
                  class="work-image"
                  @load="finishPreview(version.id, $event, 'ready')"
                  @error="finishPreview(version.id, $event, 'error')"
                />
              </div>
              <el-button :icon="Document" @click="openFile(version.fileId)"
                >打开作品文件</el-button
              >
              <p class="pre-wrap">
                {{ version.description || "未填写作品说明" }}
              </p>
            </div>
            <div class="review-heading">
              <h4>教师点评</h4>
              <el-button
                v-if="isStaff && writable"
                link
                type="primary"
                @click="addReview(version)"
                >添加点评</el-button
              >
            </div>
            <div v-if="!version.reviews?.length" class="muted review-empty">
              教师暂未点评
            </div>
            <div
              v-for="review in version.reviews || []"
              :key="review.id"
              class="review-item"
            >
              <div>
                <strong>{{
                  review.teacherName || review.reviewerName || "任课教师"
                }}</strong
                ><span v-if="review.score != null" class="review-score"
                  >{{ review.score }} 分</span
                ><small>{{ dateText(review.createdAt) }}</small>
              </div>
              <p class="pre-wrap">{{ review.content }}</p>
            </div>
          </article>
        </template>
      </div></el-drawer
    >

    <el-dialog
      v-model="noticeVisible"
      :title="currentNotice?.title || '通知'"
      width="640px"
      class="responsive-dialog"
      ><p class="muted">
        {{ dateText(currentNotice?.createdAt) }} ·
        {{
          currentNotice?.className ||
          valueLabel(currentNotice?.classId, "classes") ||
          "全体成员"
        }}
      </p>
      <div class="notice-content pre-wrap">{{ currentNotice?.content }}</div>
      <template #footer
        ><el-button type="primary" @click="noticeVisible = false"
          >知道了</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
