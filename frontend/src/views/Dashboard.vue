<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { Refresh, ArrowRight } from "@element-plus/icons-vue";
import { api } from "../api";
import { auth, roleLabel } from "../auth";
import { dateText } from "../pages";
const router = useRouter();
const data = ref(null),
  notices = ref([]),
  works = ref([]),
  tasks = ref([]),
  feedback = ref([]);
const loading = ref(false),
  failed = ref(false),
  refreshedAt = ref("");
const noticeVisible = ref(false),
  currentNotice = ref(null),
  now = ref(new Date());
let requestId = 0;
const isAdmin = computed(() => auth.user?.role === "ADMIN");
const isTeacher = computed(() => auth.user?.role === "TEACHER");
const isStudent = computed(() => auth.user?.role === "STUDENT");
const guardian = computed(() => Boolean(auth.user?.readOnly));
const chinaToday = computed(() =>
  new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now.value),
);
const todayLabel = computed(() =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long",
  }).format(now.value),
);
const total = (key) =>
  (data.value?.progress || []).reduce(
    (sum, row) => sum + Number(row[key] || 0),
    0,
  );
const learning = computed(() => ({
  expected: total("expected"),
  completed: total("completed"),
  leave: total("leaveCount"),
  pending: total("pendingMakeup"),
}));
const progressPercent = computed(() =>
  learning.value.expected
    ? Math.min(
        100,
        Math.round((learning.value.completed / learning.value.expected) * 100),
      )
    : 0,
);
const unread = computed(() => notices.value.filter((n) => !n.read).length);
const unreviewed = computed(
  () => works.value.filter((w) => !Number(w.reviewCount)).length,
);
const pendingTasks = computed(() =>
  tasks.value.filter(
    (task) =>
      task.submissionAllowed !== false &&
      (!task.dueDate || task.dueDate >= chinaToday.value) &&
      !works.value.some((work) => work.taskId === task.id),
  ),
);
const pendingFeedback = computed(
  () => feedback.value.filter((f) => f.status === "OPEN").length,
);
const upcoming = computed(() =>
  (data.value?.upcomingLessons || []).slice(0, 6),
);
const recentWorks = computed(() =>
  [...works.value]
    .sort((a, b) =>
      String(b.updatedAt || b.createdAt).localeCompare(
        String(a.updatedAt || a.createdAt),
      ),
    )
    .slice(0, 4),
);
const rate = computed(
  () => `${Number(data.value?.attendanceRate || 0).toFixed(1)}%`,
);
const statItems = computed(() =>
  isStudent.value
    ? [
        {
          label: guardian.value ? "学员应完成课次" : "应完成课次",
          value: learning.value.expected,
          unit: "次",
          note: "含已排未来正常课次",
          path: "/progress",
        },
        {
          label: "已完成课次",
          value: learning.value.completed,
          unit: "次",
          note: "含已出勤完成的补课",
          path: "/progress",
        },
        {
          label: "作品档案",
          value: data.value?.workCount,
          unit: "份",
          note: "保留作品版本与点评",
          path: "/works",
        },
        {
          label: "出勤率",
          value: rate.value,
          note: "按已结束原课次统计",
          path: "/progress",
        },
      ]
    : [
        {
          label: isAdmin.value ? "学员总数" : "任课学员",
          value: data.value?.studentCount,
          unit: "人",
          note: isAdmin.value ? "已建立学员档案" : "本人任课范围内学员",
          path: "/students",
        },
        {
          label: isAdmin.value ? "班级总数" : "任课班级",
          value: data.value?.classCount,
          unit: "个",
          note: "含保留历史记录的班级",
          path: "/classes",
        },
        {
          label: "作品档案",
          value: data.value?.workCount,
          unit: "份",
          note: "按作品计数，不重复计算版本",
          path: "/works",
        },
        {
          label: "出勤率",
          value: rate.value,
          note: "按已结束原课次统计",
          path: "/progress",
        },
      ],
);
const todoItems = computed(() => {
  const base = [
    {
      label: isStudent.value ? "请假办理中" : "待审批请假",
      count: data.value?.pendingLeaveCount || 0,
      note: isStudent.value ? "等待教师或管理员处理" : "核对申请后完成审批",
      path: "/leaves",
    },
    {
      label: "待完成补课",
      count: data.value?.pendingMakeupCount || 0,
      note: "含尚未安排和已安排未完成",
      path: isTeacher.value ? "/makeup-suggestions" : "/makeups",
    },
  ];
  if (isAdmin.value)
    return [
      ...base,
      {
        label: "待回复反馈",
        count: pendingFeedback.value,
        note: "尚未处理的意见与建议",
        path: "/feedback",
      },
      {
        label: "尚无点评作品",
        count: unreviewed.value,
        note: "作品所有版本均无教师点评",
        path: "/works",
      },
    ];
  if (isTeacher.value)
    return [
      ...base,
      {
        label: "尚无点评作品",
        count: unreviewed.value,
        note: "作品所有版本均无教师点评",
        path: "/works",
      },
      {
        label: "未读通知",
        count: unread.value,
        note: "查看最新教学安排",
        path: "/notices",
      },
    ];
  return [
    ...base,
    guardian.value
      ? {
          label: "等待点评作品",
          count: unreviewed.value,
          note: "当前尚未收到教师点评",
          path: "/works",
        }
      : {
          label: "待提交任务",
          count: pendingTasks.value.length,
          note: "当前可提交且尚未提交作品",
          path: "/works",
        },
    {
      label: "未读通知",
      count: unread.value,
      note: "查看班级和全体通知",
      path: "/notices",
    },
  ];
});
const shortcuts = computed(() =>
  isAdmin.value
    ? [
        ["学员档案", "/students"],
        ["报名与转班", "/enrollments"],
        ["课表与考勤", "/lessons"],
        ["补课安排", "/makeups"],
        ["作品档案", "/works"],
        ["账户与授权", "/users"],
      ]
    : isTeacher.value
      ? [
          ["任课学员", "/students"],
          ["课表与考勤", "/lessons"],
          ["教学任务", "/tasks"],
          ["作品点评", "/works"],
          ["教学资源", "/resources"],
          ["学习进度", "/progress"],
        ]
      : guardian.value
        ? [
            ["学员课表", "/lessons"],
            ["作品档案", "/works"],
            ["学习进度", "/progress"],
            ["通知公告", "/notices"],
            ["教学资源", "/resources"],
            ["账户资料", "/settings"],
          ]
        : [
            ["我的课表", "/lessons"],
            ["教学任务", "/tasks"],
            ["作品档案", "/works"],
            ["请假申请", "/leaves"],
            ["学习进度", "/progress"],
            ["账户设置", "/settings"],
          ],
);
function visit(path) {
  router.push(path);
}
function lessonState(row) {
  return new Date(`${row.startTime}+08:00`).getTime() <= now.value.getTime()
    ? "进行中"
    : "待上课";
}
async function load() {
  const current = ++requestId;
  loading.value = true;
  failed.value = false;
  try {
    const result = await Promise.all([
      api.get("/dashboard"),
      api.get("/notices"),
      api.get("/works"),
      isAdmin.value ? api.get("/feedback") : Promise.resolve([]),
      isStudent.value && !guardian.value
        ? api.get("/tasks")
        : Promise.resolve([]),
    ]);
    if (current !== requestId) return;
    [data.value, notices.value, works.value, feedback.value, tasks.value] =
      result;
    now.value = new Date();
    refreshedAt.value = new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      hour: "2-digit",
      minute: "2-digit",
    }).format(now.value);
  } catch {
    if (current === requestId) failed.value = true;
  } finally {
    if (current === requestId) loading.value = false;
  }
}
async function openNotice(notice) {
  currentNotice.value = notice;
  noticeVisible.value = true;
  try {
    await api.post(`/notices/${notice.id}/read`);
    notice.read = true;
  } catch {}
}
onMounted(load);
onBeforeUnmount(() => {
  ++requestId;
});
</script>

<template>
  <div v-loading="loading" class="home-overview">
    <div class="home-heading">
      <div>
        <h1>首页</h1>
        <p>
          {{ auth.user?.displayName }}，欢迎使用画室学员管理系统。<span>{{
            roleLabel(auth.user)
          }}</span>
        </p>
      </div>
      <div class="home-date">
        <time>{{ todayLabel }}</time
        ><el-button :icon="Refresh" :disabled="loading" @click="load"
          >刷新</el-button
        >
      </div>
    </div>
    <el-alert
      v-if="failed"
      type="error"
      :closable="false"
      class="home-error"
      :title="
        data
          ? '首页更新失败，当前保留上次加载的数据，请刷新重试。'
          : '首页数据加载失败，请刷新重试。'
      "
    />
    <div class="overview-stats">
      <button
        v-for="item in statItems"
        :key="item.label"
        class="stat-cell"
        @click="visit(item.path)"
      >
        <span>{{ item.label }}</span>
        <div>
          <strong>{{ data ? item.value : "—" }}</strong
          ><small v-if="data && item.unit">{{ item.unit }}</small>
        </div>
        <p>{{ item.note }}</p>
      </button>
    </div>
    <section class="panel shortcut-panel" aria-label="常用入口">
      <h2>常用入口</h2>
      <div>
        <button
          v-for="[title, path] in shortcuts"
          :key="path"
          @click="visit(path)"
        >
          {{ title }}<el-icon><ArrowRight /></el-icon>
        </button>
      </div>
    </section>
    <div class="home-columns">
      <div class="home-column">
        <section class="panel">
          <div class="panel-heading">
            <h2>
              近期课程<span class="heading-note"
                >接下来 {{ upcoming.length }} 次课</span
              >
            </h2>
            <el-button link type="primary" @click="visit('/lessons')"
              >完整课表</el-button
            >
          </div>
          <el-table
            :data="upcoming"
            class="overview-table"
            empty-text="暂无待上课程，请到课表查看历史安排"
          >
            <el-table-column label="时间" width="122"
              ><template #default="{ row }"
                ><span>{{ row.startTime?.slice(0, 10) }}</span
                ><small class="table-sub"
                  >{{ row.startTime?.slice(11, 16) }}–{{
                    row.endTime?.slice(11, 16)
                  }}</small
                ></template
              ></el-table-column
            >
            <el-table-column label="课程安排" min-width="185"
              ><template #default="{ row }"
                ><button class="inline-link" @click="visit('/lessons')">
                  {{ row.title }}</button
                ><small class="table-sub">{{ row.className }}</small></template
              ></el-table-column
            >
            <el-table-column label="教师 / 教室" min-width="120"
              ><template #default="{ row }"
                >{{ row.teacherName
                }}<small class="table-sub">{{ row.roomName }}</small></template
              ></el-table-column
            >
            <el-table-column label="状态" width="80"
              ><template #default="{ row }"
                ><span
                  :class="{ 'in-session': lessonState(row) === '进行中' }"
                  >{{ lessonState(row) }}</span
                ></template
              ></el-table-column
            >
          </el-table>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <h2>
              {{ isStudent ? "作品与点评" : "作品动态"
              }}<span class="heading-note">按最近更新排序</span>
            </h2>
            <el-button link type="primary" @click="visit('/works')"
              >全部作品</el-button
            >
          </div>
          <div v-if="!recentWorks.length" class="home-empty">
            暂无作品记录，提交作品后将在这里显示。
          </div>
          <div v-else class="activity-list">
            <button
              v-for="work in recentWorks"
              :key="work.id"
              @click="visit('/works')"
            >
              <div class="activity-main">
                <strong>{{ work.title }}</strong
                ><small
                  >{{ work.studentName }} ·
                  {{ work.taskTitle || work.className }} · 第
                  {{ work.latestVersion || work.versionCount }} 版</small
                >
              </div>
              <div class="activity-meta">
                <span>{{
                  Number(work.reviewCount)
                    ? `${work.reviewCount} 条点评`
                    : "尚无点评"
                }}</span
                ><time>{{ dateText(work.updatedAt || work.createdAt) }}</time>
              </div>
            </button>
          </div>
        </section>
        <section class="panel learning-panel">
          <div class="panel-heading">
            <h2>
              学习概况<span class="heading-note">{{
                isStudent
                  ? guardian
                    ? "绑定学员的学习记录"
                    : "本人的学习记录"
                  : "当前可查看范围内的学员"
              }}</span>
            </h2>
            <el-button link type="primary" @click="visit('/progress')"
              >详细进度</el-button
            >
          </div>
          <div class="learning-body">
            <div class="learning-counts">
              <span
                >应完成
                <strong>{{ data ? learning.expected : "—" }}</strong> 次</span
              ><span
                >已完成
                <strong>{{ data ? learning.completed : "—" }}</strong> 次</span
              ><span
                >请假记录
                <strong>{{ data ? learning.leave : "—" }}</strong> 次</span
              ><span
                >待完成补课
                <strong>{{ data ? learning.pending : "—" }}</strong> 次</span
              >
            </div>
            <el-progress
              :percentage="progressPercent"
              :stroke-width="8"
              :show-text="false"
            />
            <p>
              完成进度
              {{
                data ? progressPercent + "%" : "—"
              }}，应完成量包含已排未来课次；补课完成只计入一次。
            </p>
          </div>
        </section>
      </div>
      <div class="home-column">
        <section class="panel">
          <div class="panel-heading">
            <h2>{{ guardian ? "学员事项" : "待办事项" }}</h2>
            <span class="heading-note">{{
              refreshedAt ? `${refreshedAt} 更新` : "等待加载"
            }}</span>
          </div>
          <div class="todo-list">
            <button
              v-for="item in todoItems"
              :key="item.label"
              @click="visit(item.path)"
            >
              <span
                >{{ item.label }}<small>{{ item.note }}</small></span
              ><strong :class="{ outstanding: item.count > 0 }">{{
                data ? item.count : "—"
              }}</strong
              ><el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <h2>
              通知公告<span v-if="unread" class="heading-note"
                >{{ unread }} 条未读</span
              >
            </h2>
            <el-button link type="primary" @click="visit('/notices')"
              >全部通知</el-button
            >
          </div>
          <div v-if="!notices.length" class="home-empty">暂无通知公告。</div>
          <div v-else class="bulletin-list">
            <button
              v-for="notice in notices.slice(0, 5)"
              :key="notice.id"
              @click="openNotice(notice)"
            >
              <div>
                <span
                  v-if="!notice.read"
                  class="unread-dot"
                  aria-label="未读"
                ></span
                ><strong>{{ notice.title }}</strong>
              </div>
              <p>
                {{ notice.className || "全体成员"
                }}<time>{{ dateText(notice.createdAt).slice(0, 10) }}</time>
              </p>
            </button>
          </div>
        </section>
        <section v-if="isStudent && !guardian" class="panel">
          <div class="panel-heading">
            <h2>待提交任务</h2>
            <el-button link type="primary" @click="visit('/tasks')"
              >全部任务</el-button
            >
          </div>
          <div v-if="!pendingTasks.length" class="home-empty">
            暂无待提交任务。
          </div>
          <div v-else class="bulletin-list">
            <button
              v-for="task in pendingTasks.slice(0, 4)"
              :key="task.id"
              @click="visit('/works')"
            >
              <strong>{{ task.title }}</strong>
              <p>
                {{ task.className
                }}<span>{{
                  task.dueDate ? `截止 ${task.dueDate}` : "未设截止日期"
                }}</span>
              </p>
            </button>
          </div>
        </section>
      </div>
    </div>
    <el-dialog
      v-model="noticeVisible"
      :title="currentNotice?.title || '通知详情'"
      width="620px"
      class="responsive-dialog"
      ><p class="notice-meta">
        {{ currentNotice?.className || "全体成员" }} ·
        {{ currentNotice?.creatorName }} ·
        {{ dateText(currentNotice?.createdAt) }}
      </p>
      <div class="notice-body">{{ currentNotice?.content }}</div>
      <template #footer
        ><el-button @click="noticeVisible = false">关闭</el-button></template
      ></el-dialog
    >
  </div>
</template>

<style scoped>
.home-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
  margin-bottom: 18px;
}
.home-heading h1 {
  margin: 0 0 5px;
}
.home-heading p {
  margin: 0;
  font-size: 13px;
  color: #747a82;
  line-height: 1.8;
}
.home-heading p span {
  margin-left: 12px;
  color: #949aa2;
  font-size: 12px;
}
.home-date {
  display: flex;
  align-items: center;
  gap: 16px;
  color: #747a82;
  white-space: nowrap;
  font-size: 12px;
}
.home-error {
  margin-bottom: 14px;
}
.overview-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid #dce0e6;
  background: #fff;
  margin-bottom: 14px;
}
.stat-cell {
  display: block;
  min-width: 0;
  padding: 16px 20px 14px;
  text-align: left;
  background: none;
  border: 0;
  border-right: 1px solid #e7e9ed;
  color: #50545b;
}
.stat-cell:last-child {
  border-right: 0;
}
.stat-cell:hover {
  background: #fafbfc;
}
.stat-cell > span {
  font-size: 13px;
}
.stat-cell > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 7px;
}
.stat-cell strong {
  font-size: 28px;
  line-height: 1.3;
  font-weight: 600;
  color: #3168a8;
  font-variant-numeric: tabular-nums;
}
.stat-cell small {
  font-size: 12px;
  color: #8b929b;
}
.stat-cell p {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: #8b929b;
}
.shortcut-panel {
  display: flex;
  gap: 20px;
  align-items: center;
  padding: 12px 16px;
  margin-bottom: 16px;
}
.shortcut-panel h2 {
  flex: 0 0 auto;
  font-size: 13px;
  color: #50545b;
}
.shortcut-panel > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.shortcut-panel button {
  display: inline-flex;
  align-items: center;
  gap: 14px;
  padding: 6px 10px;
  border: 1px solid #dfe3e8;
  border-radius: 2px;
  background: #fff;
  color: #50545b;
  font-size: 12px;
}
.shortcut-panel button:hover {
  color: #3168a8;
  border-color: #99b3d2;
  background: #f8fafc;
}
.shortcut-panel .el-icon {
  color: #969da6;
  font-size: 11px;
}
.home-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(300px, 1fr);
  gap: 16px;
  align-items: start;
}
.home-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.panel-heading h2 {
  font-size: 14px;
}
.heading-note {
  margin-left: 10px;
  font-weight: 400;
  font-size: 11px;
  color: #9097a0;
}
.panel-heading > .heading-note {
  margin-left: 0;
}
.overview-table :deep(.el-table__cell) {
  padding-top: 10px;
  padding-bottom: 10px;
}
.table-sub {
  display: block;
  font-size: 11px;
  color: #8a919a;
  margin-top: 4px;
}
.inline-link {
  display: block;
  text-align: left;
  color: #3b4149;
  padding: 0;
  background: none;
  border: 0;
  line-height: 1.6;
}
.inline-link:hover,
.in-session {
  color: #3168a8;
}
.todo-list {
  padding: 0 14px;
}
.todo-list button {
  display: flex;
  gap: 12px;
  width: 100%;
  align-items: center;
  padding: 13px 0;
  background: none;
  border: 0;
  border-bottom: 1px solid #eceef1;
  text-align: left;
  color: #50545b;
}
.todo-list button:last-child {
  border-bottom: 0;
}
.todo-list button > span {
  flex: 1;
}
.todo-list small {
  display: block;
  margin-top: 5px;
  font-size: 11px;
  color: #9298a0;
}
.todo-list strong {
  font-size: 20px;
  font-weight: 500;
  color: #9aa1aa;
  font-variant-numeric: tabular-nums;
}
.todo-list strong.outstanding {
  color: #3168a8;
}
.todo-list .el-icon {
  color: #a0a6ae;
  font-size: 12px;
}
.todo-list button:hover > span {
  color: #3168a8;
}
.home-empty {
  padding: 30px 16px;
  text-align: center;
  font-size: 12px;
  line-height: 1.8;
  color: #9299a1;
}
.activity-list {
  padding: 0 14px;
}
.activity-list > button {
  display: flex;
  width: 100%;
  gap: 12px;
  justify-content: space-between;
  align-items: center;
  padding: 13px 0;
  border: 0;
  border-bottom: 1px solid #eceef1;
  background: none;
  text-align: left;
}
.activity-list > button:last-child {
  border-bottom: 0;
}
.activity-main {
  min-width: 0;
}
.activity-main strong {
  display: block;
  font-weight: 400;
  color: #414750;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.activity-main small {
  display: block;
  color: #8b929b;
  font-size: 11px;
  margin-top: 5px;
  line-height: 1.6;
}
.activity-meta {
  flex: 0 0 auto;
  text-align: right;
  font-size: 11px;
  color: #6a7480;
}
.activity-meta time {
  display: block;
  color: #989fa7;
  margin-top: 5px;
}
.activity-list button:hover strong {
  color: #3168a8;
}
.bulletin-list {
  padding: 0 14px;
}
.bulletin-list button {
  display: block;
  width: 100%;
  padding: 14px 0;
  background: none;
  border: 0;
  border-bottom: 1px solid #eceef1;
  text-align: left;
}
.bulletin-list button:last-child {
  border-bottom: 0;
}
.bulletin-list button > div {
  display: flex;
  align-items: center;
  gap: 7px;
}
.bulletin-list strong {
  font-size: 13px;
  font-weight: 400;
  color: #414750;
  line-height: 1.6;
}
.bulletin-list button:hover strong {
  color: #3168a8;
}
.bulletin-list p {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin: 6px 0 0;
  font-size: 11px;
  color: #939aa3;
  line-height: 1.6;
}
.unread-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #3168a8;
  flex: 0 0 auto;
}
.learning-body {
  padding: 16px;
}
.learning-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  font-size: 12px;
  color: #737c86;
  margin-bottom: 14px;
}
.learning-counts strong {
  color: #39424d;
  font-size: 14px;
  font-weight: 500;
  margin: 0 3px;
}
.learning-body p {
  margin: 9px 0 0;
  color: #949ba4;
  font-size: 11px;
  line-height: 1.7;
}
.notice-meta {
  color: #8a929c;
  font-size: 12px;
  line-height: 1.8;
}
.notice-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  padding: 4px 0 14px;
}
@media (max-width: 1100px) {
  .home-columns {
    grid-template-columns: minmax(0, 1.3fr) minmax(280px, 1fr);
  }
  .stat-cell {
    padding: 14px;
  }
  .home-heading p span,
  .home-date time,
  .heading-note {
    display: none;
  }
}
@media (max-width: 800px) {
  .home-columns {
    grid-template-columns: 1fr;
  }
  .home-column:last-child {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .home-heading {
    gap: 10px;
    align-items: flex-start;
  }
  .home-heading p {
    font-size: 12px;
  }
  .overview-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .stat-cell:nth-child(2) {
    border-right: 0;
  }
  .stat-cell:nth-child(-n + 2) {
    border-bottom: 1px solid #e7e9ed;
  }
  .shortcut-panel {
    align-items: flex-start;
    gap: 12px;
  }
  .shortcut-panel h2 {
    padding-top: 5px;
  }
}
@media (max-width: 540px) {
  .home-heading h1 {
    font-size: 18px;
  }
  .stat-cell {
    padding: 12px;
  }
  .stat-cell strong {
    font-size: 24px;
  }
  .stat-cell p {
    font-size: 10px;
  }
  .shortcut-panel {
    display: block;
    padding: 12px;
  }
  .shortcut-panel h2 {
    padding: 0;
    margin-bottom: 10px;
  }
  .shortcut-panel > div {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .shortcut-panel button {
    justify-content: space-between;
  }
  .home-column:last-child {
    display: flex;
  }
  .activity-meta time {
    display: none;
  }
  .activity-main small {
    overflow-wrap: anywhere;
  }
  .learning-counts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
}
</style>
