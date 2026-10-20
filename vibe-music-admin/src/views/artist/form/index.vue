<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { ElMessage } from "element-plus";
import ReCol from "@/components/ReCol";
import ProfilePreview from "./ProfilePreview.vue";
import EyeIcon from "@iconify-icons/ri/eye-line";
import KeyboardIcon from "@iconify-icons/ri/keyboard-line";
import LoaderIcon from "@iconify-icons/ri/loader-4-line";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";
import {
  searchQqArtists,
  getQqArtistProfile,
  type ArtistCandidate,
  type ArtistProfile
} from "@/api/artistProfile";

const props = withDefaults(defineProps<FormProps>(), {
  formInline: () => ({
    title: "新增",
    artistId: 0,
    artistName: "",
    gender: 0,
    birth: null,
    area: "",
    introduction: "",
    status: "ACTIVE",
    avatarFile: null,
    avatarPreview: "",
    qqSingerMid: "",
    qqAvatarSize: 500
  })
});

const genderOptions = [
  {
    value: 0,
    label: "男歌手"
  },
  {
    value: 1,
    label: "女歌手"
  },
  {
    value: 2,
    label: "组合/乐队"
  }
];

const statusOptions = [
  {
    value: "ACTIVE",
    label: "启用"
  },
  {
    value: "DISABLED",
    label: "停用（软删除，用户端不可见）"
  }
];

/** 头像体积上限，与后端 ArtistServiceImpl.MAX_AVATAR_BYTES 保持一致 */
const MAX_AVATAR_BYTES = 5 * 1024 * 1024;

const ruleFormRef = ref();
const newFormInline = ref(props.formInline);

const isCreate = computed(() => newFormInline.value.title === "新增");

/**
 * 是否用 QQ 资料覆盖「已有内容」。
 *
 * <p>两种模式的语义不同，所以做成开关而不是固定行为：
 * <ul>
 *   <li><b>新增</b>：表单基本是空的，谈不上覆盖；这里恒为 false 也不影响结果。</li>
 *   <li><b>修改</b>：库里已有资料。默认「只填空缺」会让人以为点了没反应
 *       （例如简介本来就有，点候选后毫无变化），所以修改模式默认<b>覆盖</b>；
 *       但用户手填过的内容仍可能更准，因此保留一个开关随时关掉。</li>
 * </ul>
 */
const qqOverwrite = ref(!isCreate.value);

// ------------------------------------------------------------------ QQ 音乐搜索

const qqKeyword = ref("");
const qqSearching = ref(false);
const qqCandidates = ref<ArtistCandidate[]>([]);
/** 已经搜过一次（用于区分「还没搜」和「搜了但没有结果」两种空状态） */
const qqSearched = ref(false);
/** 正在取详情的 mid，用于在候选行上显示 loading */
const qqLoadingMid = ref("");
/**
 * 当前预览的那位歌手的完整资料。
 *
 * <p>点候选<b>只预览、不写表单</b>：搜索结果只有名字和头像，管理员得先看清
 * 生日、国籍、简介、代表作，才知道要不要用它覆盖表单里的内容。
 * 真正的写入由「填充到表单」按钮或 Alt + Enter 触发。
 */
const qqPreview = ref<ArtistProfile | null>(null);
/** 当前预览的资料是否已经填进表单了 */
const qqPreviewApplied = ref(false);

/**
 * 执行一次搜索。
 *
 * <p>抽成独立函数是因为 Alt + Enter 要「搜索 → 挑精确匹配 → 预览 → 填充」一气呵成，
 * 不该在两条路径里各写一遍错误处理。
 *
 * <p>搜完顺手预览一位：预览是只读的，不会改表单，所以不存在「悄悄填错人」的风险；
 * 而搜索结果通常只有一位，让人再点一下纯属多余。有精确匹配就预览它，
 * 否则只在结果唯一时预览（多条又不精确时，让人自己点）。
 *
 * @returns 候选列表；失败时为空数组
 */
async function runSearch(keyword: string): Promise<ArtistCandidate[]> {
  qqSearching.value = true;
  // 换关键词重新搜之后，上一位的预览就不再对应任何候选了
  qqPreview.value = null;
  qqPreviewApplied.value = false;
  try {
    const res = await searchQqArtists(keyword);
    if (res.code !== 0) {
      qqCandidates.value = [];
      ElMessage.error(res.message ?? "搜索失败");
      return [];
    }
    qqCandidates.value = res.data ?? [];
    if (!qqCandidates.value.length) {
      ElMessage.info("没搜到这位歌手，可以手动填写下面的资料");
      return [];
    }
    const exact = qqCandidates.value.find(item => item.exactMatch);
    const autoPreview =
      exact ?? (qqCandidates.value.length === 1 ? qqCandidates.value[0] : null);
    if (autoPreview) {
      await previewCandidate(autoPreview);
    }
    return qqCandidates.value;
  } catch (error) {
    qqCandidates.value = [];
    const detail = (error as any)?.response?.data?.message ?? "";
    ElMessage.error(`搜索失败${detail ? "：" + detail : ""}`);
    return [];
  } finally {
    qqSearching.value = false;
    qqSearched.value = true;
  }
}

/** 按搜索框里的关键词搜索（Enter 或点「搜索」）。 */
async function handleQqSearch() {
  const keyword = qqKeyword.value.trim();
  if (!keyword) {
    ElMessage.warning("请先输入歌手名");
    return;
  }
  await runSearch(keyword);
}

/**
 * 取某位候选的完整资料并显示预览。
 *
 * <p>刻意<b>不</b>顺手填表单：预览就是给人看的，看了再决定填不填。
 * 尤其修改模式下，直接覆盖会让人还没看清就把简介换掉了。
 */
async function previewCandidate(candidate: ArtistCandidate) {
  qqLoadingMid.value = candidate.singerMid;
  try {
    const res = await getQqArtistProfile(candidate.singerMid);
    if (res.code !== 0 || !res.data) {
      ElMessage.error(res.message ?? "没取到这位歌手的资料");
      return;
    }
    qqPreview.value = res.data;
    qqPreviewApplied.value = false;
  } catch (error) {
    const detail = (error as any)?.response?.data?.message ?? "";
    ElMessage.error(`获取资料失败${detail ? "：" + detail : ""}`);
  } finally {
    qqLoadingMid.value = "";
  }
}

/** 把当前预览的资料填进表单（点「填充到表单」）。 */
function applyPreview() {
  if (!qqPreview.value) {
    return;
  }
  applyProfile(qqPreview.value);
  qqPreviewApplied.value = true;
}

/**
 * Alt + Enter：一键自动灌装。
 *
 * <p>搜索框为空时用表单里已填的歌手名（修改歌手时就是这位歌手的名字），
 * 所以改老歌手时不用再手打一遍名字。
 *
 * <p>只认「精确匹配」的候选：实测 LANY 的第一条是「Blue」、Christopher 的第一条是
 * 「黄凯芹」，按第一条盲填会把别人的资料写进来。没有精确匹配时只列出候选，
 * 让人自己选 —— 宁可多一次点击，也不要悄悄填错人。
 */
async function autoFillFromArtistName() {
  const keyword = (
    qqKeyword.value ||
    newFormInline.value.artistName ||
    ""
  ).trim();
  if (!keyword) {
    ElMessage.warning("请先填写歌手名，或在搜索框里输入关键词");
    return;
  }
  // 用户要的效果：按下 Alt + Enter，搜索框里直接补上歌手名
  qqKeyword.value = keyword;

  const candidates = await runSearch(keyword);
  if (!candidates.length) {
    return;
  }
  const exact = candidates.find(item => item.exactMatch);
  if (!exact) {
    ElMessage.warning(`没有与「${keyword}」完全一致的候选，请在下面手动选一位`);
    return;
  }
  // runSearch 已经预览过精确匹配的那位；万一没有（理论上不会），这里补一次
  if (qqPreview.value?.singerMid !== exact.singerMid) {
    await previewCandidate(exact);
  }
  if (qqPreview.value) {
    applyPreview();
  }
}

/**
 * 搜索框按键：Enter 搜索。
 *
 * <p>Alt + Enter 交给全局监听处理（见 {@link handleGlobalKeydown}）：这样光标停在
 * 歌手名、简介甚至「确定」按钮上时，快捷键照样有效 —— 用户说的是「我按下 Alt + Enter」，
 * 不该要求他先把光标挪到某个输入框里。
 *
 * <p>必须挡掉输入法组合状态：中文歌手名几乎都是打拼音再选词，此时按 Enter 是
 * 「确认候选词」，若被当成搜索，会拿半截拼音去查。{@code keyCode === 229} 是
 * 部分浏览器在组合期间给的值，一并挡掉。
 */
function handleQqKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter" || event.isComposing || event.keyCode === 229) {
    return;
  }
  if (event.altKey) {
    // 交给 document 上的全局监听，这里不处理，避免同一件事触发两次
    return;
  }
  event.preventDefault();
  void handleQqSearch();
}

/**
 * 全局 Alt + Enter：自动灌装。
 *
 * <p>监听 document 而不是某个输入框，是因为本组件只在弹窗打开期间存在
 * （{@code contentRenderer} 每次开窗新建），所以这段时间里的 Alt + Enter
 * 一定是在对这张表单说话。Alt + Enter 本身也不是浏览器的常用快捷键，
 * 不会抢走别的行为。
 */
function handleGlobalKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter" || !event.altKey) {
    return;
  }
  if (event.isComposing || event.keyCode === 229) {
    return;
  }
  event.preventDefault();
  void autoFillFromArtistName();
}

/**
 * 把 QQ 资料写进表单。
 *
 * <p>覆盖策略由 {@link qqOverwrite} 决定：
 * <ul>
 *   <li>{@code true}（修改模式默认）—— 用 QQ 的值覆盖，但会明确告诉用户覆盖了哪几项；</li>
 *   <li>{@code false}（新增模式默认）—— 只填空字段，已有内容原样保留。</li>
 * </ul>
 *
 * <p>无论哪种模式都<b>不会自动提交</b>：填充只是省去打字，最终写库仍要人点「确定」。
 * 这一点对修改模式尤其重要 —— 覆盖掉的值如果自动保存，就没有挽回余地了。
 */
function applyProfile(profile: {
  artistName?: string | null;
  gender?: number | null;
  birth?: string | null;
  area?: string | null;
  introduction?: string | null;
  avatarUrls?: Record<string, string> | null;
  hasAvatar?: boolean | null;
  filledFields?: string[] | null;
  singerMid: string;
}) {
  const form = newFormInline.value;
  const overwrite = qqOverwrite.value;
  const filled: string[] = [];
  const kept: string[] = [];

  /**
   * 写入单个字段：`overwrite` 为真时直接覆盖，否则只在原值为空时填。
   * 返回是否真的写入了。
   */
  const write = (
    label: string,
    next: string | number | null | undefined,
    current: unknown,
    apply: () => void
  ) => {
    if (next == null || next === "") {
      return;
    }
    const hasCurrent = current != null && String(current).trim() !== "";
    if (overwrite || !hasCurrent) {
      apply();
      filled.push(label);
    } else {
      kept.push(label);
    }
  };

  // 名字总是用选中的那位覆盖：用户点这个候选就是为了换人
  if (profile.artistName) {
    form.artistName = profile.artistName;
    filled.push("歌手名");
  }

  write("类型", profile.gender, form.gender, () => {
    form.gender = profile.gender as number;
  });

  write("生日", profile.birth, form.birth, () => {
    form.birth = profile.birth as any;
  });

  write("国籍", profile.area, form.area, () => {
    form.area = profile.area as string;
  });

  write("简介", profile.introduction, form.introduction, () => {
    form.introduction = profile.introduction as string;
  });

  // 头像：只有对方确实有图才接管预览。地址能拼出来不代表图存在。
  if (profile.hasAvatar && profile.avatarUrls?.["500"]) {
    clearAvatar();
    form.avatarPreview = profile.avatarUrls["500"];
    form.qqSingerMid = profile.singerMid;
    filled.push("头像");
  }

  const parts: string[] = [];
  if (filled.length) {
    parts.push(`${overwrite ? "已覆盖" : "已填充"}：${filled.join("、")}`);
  }
  if (kept.length) {
    parts.push(`保留了原有：${kept.join("、")}`);
  }
  if (parts.length) {
    ElMessage.success(parts.join("；"));
  } else {
    ElMessage.info("这位歌手没有可填充的资料");
  }
}

/** 清掉「来自 QQ」的状态，恢复成原来的头像（修改模式）或空头像（新增模式）。 */
function clearQqSelection() {
  const form = newFormInline.value;
  form.qqSingerMid = "";
  if (!form.avatarFile) {
    // 当前预览不是用户上传的文件，还原成进入弹窗时的头像
    form.avatarPreview = originalAvatar.value;
  }
}

/**
 * 进入弹窗时的原头像。
 *
 * <p>「取消 QQ 填充」和「移除头像」都要能回到这里。新增模式下它是空串；
 * 修改模式下是库里已有的头像地址 —— 否则用户点了移除就再也找不回原图了
 * （表单只带 URL，不带原始文件）。
 */
const originalAvatar = ref(props.formInline.avatarPreview || "");

/** 当前是否处于「已改动头像」状态：预览地址与进入时不同。 */
const avatarChanged = computed(
  () => (newFormInline.value.avatarPreview || "") !== originalAvatar.value
);

/**
 * 选图后只做本地预览，不立即上传 —— 用户还可能改名字、甚至取消整个操作。
 * 真正上传发生在点「确定」时，由 addArtistWithAvatar / updateArtistWithAvatar 一并提交。
 */
function handleAvatarChange(uploadFile: { raw?: File }) {
  const file = uploadFile?.raw;
  if (!file) {
    return;
  }
  if (file.size > MAX_AVATAR_BYTES) {
    ElMessage.error("头像图片不能超过 5 MB");
    return;
  }
  if (!file.type?.startsWith("image/")) {
    ElMessage.error("头像仅支持 JPG、PNG、WebP 等图片格式");
    return;
  }
  // 换图时释放上一个 objectURL，否则反复选图会一直占着内存
  if (newFormInline.value.avatarPreview?.startsWith("blob:")) {
    URL.revokeObjectURL(newFormInline.value.avatarPreview);
  }
  newFormInline.value.avatarFile = file;
  newFormInline.value.avatarPreview = URL.createObjectURL(file);
  // 自己上传了图，就不再走 QQ 下载
  newFormInline.value.qqSingerMid = "";
}

/** 移除头像。修改模式下等价于「恢复原头像」，不会真的把库里的头像清空。 */
function clearAvatar() {
  if (newFormInline.value.avatarPreview?.startsWith("blob:")) {
    URL.revokeObjectURL(newFormInline.value.avatarPreview);
  }
  newFormInline.value.avatarFile = null;
  newFormInline.value.avatarPreview = originalAvatar.value;
  newFormInline.value.qqSingerMid = "";
}

function getRef() {
  return ruleFormRef.value;
}

// 弹窗打开期间才存在，因此这里挂载/卸载 document 监听是安全的：
// 关窗时组件销毁，监听随之移除，不会留到别的页面去。
onMounted(() => document.addEventListener("keydown", handleGlobalKeydown));
onBeforeUnmount(() =>
  document.removeEventListener("keydown", handleGlobalKeydown)
);

defineExpose({ getRef });
</script>

<template>
  <el-form
    ref="ruleFormRef"
    :model="newFormInline"
    :rules="formRules"
    label-width="82px"
  >
    <el-row :gutter="30">
      <re-col
        v-if="newFormInline.title === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="歌手编号" prop="artistId">
          <el-input
            v-model="newFormInline.artistId"
            disabled
            placeholder="newFormInline.artistId"
          />
        </el-form-item>
      </re-col>

      <!--
        从 QQ 音乐搜索、预览、再决定是否填充。新增与修改都可用。
        刻意做成「搜 → 预览 → 填」三步而不是「输入名字就自动填」：
        实测有 47 位歌手搜出 2 个候选，且第一条不一定对（LANY 的第一条是「Blue」，
        Christopher 的第一条是「黄凯芹」），必须由人确认是哪一位。
      -->
      <re-col :value="24" :xs="24" :sm="24">
        <el-form-item label="自动灌装">
          <div class="w-full">
            <div class="text-xs text-[var(--el-text-color-secondary)] mb-1">
              输入歌手名，快速补全歌手资料
            </div>
            <div class="flex items-center gap-2 flex-wrap">
              <el-input
                v-model="qqKeyword"
                :placeholder="
                  isCreate
                    ? '输入歌手名，从 QQ 音乐搜索资料'
                    : `输入歌手名搜索，当前：${newFormInline.artistName}`
                "
                clearable
                class="!w-[260px]"
                @keydown="handleQqKeydown"
              />
              <el-button
                type="primary"
                :loading="qqSearching"
                @click="handleQqSearch"
              >
                搜索
              </el-button>
              <!-- 覆盖开关只在修改模式有意义：新增时表单本来就空 -->
              <el-checkbox v-if="!isCreate" v-model="qqOverwrite">
                覆盖已有内容
              </el-checkbox>
              <span
                v-if="newFormInline.qqSingerMid"
                class="text-xs text-[var(--el-color-success)]"
              >
                已选用 QQ 候选，头像将由服务端下载
              </span>
            </div>

            <!-- 快捷键提示：写出来别人才知道有 Alt + Enter -->
            <div
              class="mt-1 flex items-center gap-1 text-xs text-[var(--el-text-color-secondary)]"
            >
              <IconifyIconOffline :icon="KeyboardIcon" class="shrink-0" />
              <span>按 Enter 搜索 · Alt + Enter 自动灌装</span>
            </div>

            <div
              v-if="qqCandidates.length"
              class="mt-2 grid grid-cols-2 gap-2 max-h-[210px] overflow-auto"
            >
              <div
                v-for="item in qqCandidates"
                :key="item.singerMid"
                class="flex items-center gap-2 p-2 rounded border cursor-pointer transition-colors"
                :class="
                  qqPreview?.singerMid === item.singerMid
                    ? 'border-[var(--el-color-primary)] bg-[var(--el-color-primary-light-9)]'
                    : 'border-[var(--el-border-color)] hover:border-[var(--el-color-primary-light-5)]'
                "
                :title="`点击预览 ${item.artistName} 的资料`"
                @click="previewCandidate(item)"
              >
                <el-image
                  :src="item.avatarUrls?.['300']"
                  fit="cover"
                  class="w-[44px] h-[44px] rounded-full shrink-0 bg-[var(--el-fill-color-light)]"
                >
                  <template #error>
                    <div
                      class="w-[44px] h-[44px] rounded-full bg-[var(--el-fill-color-light)] flex items-center justify-center text-xs text-[var(--el-text-color-placeholder)]"
                    >
                      无图
                    </div>
                  </template>
                </el-image>
                <div class="min-w-0 flex-1">
                  <div class="truncate text-sm" :title="item.artistName">
                    {{ item.artistName }}
                  </div>
                  <div class="flex items-center gap-1 mt-[2px]">
                    <!-- 修改模式下「库中已有」就是当前这位，标成「当前歌手」更直观 -->
                    <el-tag
                      v-if="
                        !isCreate &&
                        item.existingArtistId === newFormInline.artistId
                      "
                      size="small"
                      type="primary"
                      effect="plain"
                    >
                      当前歌手
                    </el-tag>
                    <el-tag
                      v-else-if="item.existingInLibrary"
                      size="small"
                      type="warning"
                      effect="plain"
                    >
                      库中已有
                    </el-tag>
                    <el-tag
                      v-else-if="!item.exactMatch"
                      size="small"
                      type="info"
                      effect="plain"
                    >
                      名字不完全一致
                    </el-tag>
                    <el-tag v-else size="small" type="success" effect="plain">
                      精确匹配
                    </el-tag>
                    <!-- 已经填进表单的那位额外标一下，否则填完就看不出来是谁了 -->
                    <el-tag
                      v-if="
                        qqPreviewApplied &&
                        qqPreview?.singerMid === item.singerMid
                      "
                      size="small"
                      type="success"
                      effect="dark"
                    >
                      已填入
                    </el-tag>
                  </div>
                </div>
                <el-icon
                  v-if="qqLoadingMid === item.singerMid"
                  class="is-loading shrink-0"
                >
                  <IconifyIconOffline :icon="LoaderIcon" />
                </el-icon>
                <IconifyIconOffline
                  v-else
                  :icon="EyeIcon"
                  class="shrink-0 text-[var(--el-text-color-secondary)]"
                />
              </div>
            </div>

            <div
              v-else-if="qqSearched && !qqSearching"
              class="mt-2 text-xs text-[var(--el-text-color-secondary)]"
            >
              没搜到候选。可以直接手填下面的资料，或换个关键词再试。
            </div>

            <!-- 预览卡片：看清生日/国籍/简介/代表作之后，再决定要不要写进表单 -->
            <div v-if="qqPreview" class="mt-2">
              <ProfilePreview
                :key="qqPreview.singerMid"
                :profile="qqPreview"
                :is-current-artist="
                  !isCreate &&
                  qqPreview.existingArtistId === newFormInline.artistId
                "
                :applied="qqPreviewApplied"
                @apply="applyPreview"
              />
            </div>

            <div
              v-if="newFormInline.qqSingerMid"
              class="mt-2 text-xs text-[var(--el-text-color-secondary)]"
            >
              填充只影响表单，不会自动保存。
              <el-button
                link
                type="danger"
                size="small"
                @click="clearQqSelection"
              >
                取消 QQ 填充
              </el-button>
            </div>
          </div>
        </el-form-item>
      </re-col>

      <!-- 头像：新增时可直接选图；修改时显示当前头像并允许替换 -->
      <re-col :value="24" :xs="24" :sm="24">
        <el-form-item label="歌手头像">
          <div class="flex items-center gap-4">
            <el-image
              v-if="newFormInline.avatarPreview"
              :src="newFormInline.avatarPreview"
              fit="cover"
              class="w-[72px] h-[72px] rounded-full border border-[var(--el-border-color)]"
            >
              <template #error>
                <div
                  class="w-[72px] h-[72px] rounded-full bg-[var(--el-fill-color-light)] flex items-center justify-center text-xs text-[var(--el-text-color-placeholder)]"
                >
                  加载失败
                </div>
              </template>
            </el-image>
            <div
              v-else
              class="w-[72px] h-[72px] rounded-full bg-[var(--el-fill-color-light)] flex items-center justify-center text-[var(--el-text-color-placeholder)]"
            >
              暂无
            </div>
            <div class="flex flex-col gap-1">
              <div class="flex items-center gap-2">
                <el-upload
                  :auto-upload="false"
                  :show-file-list="false"
                  accept="image/*"
                  :on-change="handleAvatarChange"
                >
                  <el-button size="small">
                    {{ avatarChanged ? "更换图片" : "选择图片" }}
                  </el-button>
                </el-upload>
                <!-- 修改模式下「还原」比「移除」更准确：库里原有头像不会被清空 -->
                <el-button
                  v-if="avatarChanged && !isCreate"
                  size="small"
                  text
                  @click="clearAvatar"
                >
                  还原
                </el-button>
                <el-button
                  v-else-if="avatarChanged && isCreate"
                  size="small"
                  text
                  type="danger"
                  @click="clearAvatar"
                >
                  移除
                </el-button>
              </div>
              <span
                class="text-xs text-[var(--el-text-color-secondary)] leading-tight"
              >
                可选，JPG / PNG / WebP，不超过 5 MB
                <template v-if="!isCreate">；不改就保持原头像</template>
              </span>
            </div>
          </div>
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌手" prop="artistName" required>
          <el-input
            v-model="newFormInline.artistName"
            clearable
            placeholder="请输入歌手"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="类型">
          <el-select
            v-model="newFormInline.gender"
            placeholder="请选择类型"
            class="w-full"
            clearable
          >
            <el-option
              v-for="(item, index) in genderOptions"
              :key="index"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="生日" prop="birth">
          <el-date-picker
            v-model="newFormInline.birth"
            type="date"
            placeholder="请选择生日"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="国籍" prop="area">
          <el-input
            v-model="newFormInline.area"
            clearable
            placeholder="请输入国籍"
          />
        </el-form-item>
      </re-col>

      <re-col
        v-if="newFormInline.title === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="状态" prop="status">
          <el-select
            v-model="newFormInline.status"
            placeholder="请选择状态"
            class="w-full"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </re-col>

      <re-col>
        <el-form-item label="简介">
          <el-input
            v-model="newFormInline.introduction"
            placeholder="请输入简介"
            type="textarea"
            line-number
            :autosize="{ minRows: 4, maxRows: 10 }"
          />
        </el-form-item>
      </re-col>
    </el-row>
  </el-form>
</template>
