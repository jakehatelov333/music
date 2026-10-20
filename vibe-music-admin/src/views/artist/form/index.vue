<script setup lang="ts">
import { ref, computed } from "vue";
import { ElMessage } from "element-plus";
import ReCol from "@/components/ReCol";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";
import {
  searchQqArtists,
  getQqArtistProfile,
  type ArtistCandidate
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

// ------------------------------------------------------------------ QQ 音乐搜索

const qqKeyword = ref("");
const qqSearching = ref(false);
const qqCandidates = ref<ArtistCandidate[]>([]);
/** 已经搜过一次（用于区分「还没搜」和「搜了但没有结果」两种空状态） */
const qqSearched = ref(false);
/** 正在取详情的 mid，用于在候选行上显示 loading */
const qqLoadingMid = ref("");
/**
 * 记录上一次自动填充了哪些字段，便于在用户改过之后仍然只清掉「我们填的」部分。
 * 不用它做覆盖判断 —— 用户手填的内容永远优先，见 applyProfile 的注释。
 */
const lastFilledFields = ref<string[]>([]);

async function handleQqSearch() {
  const keyword = qqKeyword.value.trim();
  if (!keyword) {
    ElMessage.warning("请先输入歌手名");
    return;
  }
  qqSearching.value = true;
  try {
    const res = await searchQqArtists(keyword);
    if (res.code !== 0) {
      qqCandidates.value = [];
      ElMessage.error(res.message ?? "搜索失败");
      return;
    }
    qqCandidates.value = res.data ?? [];
    if (!qqCandidates.value.length) {
      ElMessage.info("没搜到这位歌手，可以手动填写下面的资料");
    }
  } catch (error) {
    qqCandidates.value = [];
    const detail = (error as any)?.response?.data?.message ?? "";
    ElMessage.error(`搜索失败${detail ? "：" + detail : ""}`);
  } finally {
    qqSearching.value = false;
    qqSearched.value = true;
  }
}

/**
 * 选中一位候选，取其完整资料并填充表单。
 *
 * 刻意<b>不</b>自动提交：填充只是省去打字，最终写库仍要人点「确定」。
 * 同理，已经有值的字段不会被覆盖 —— 用户可能在搜之前就手填了内容，
 * 那些是更可靠的信息。
 */
async function handleSelectCandidate(candidate: ArtistCandidate) {
  qqLoadingMid.value = candidate.singerMid;
  try {
    const res = await getQqArtistProfile(candidate.singerMid);
    if (res.code !== 0 || !res.data) {
      ElMessage.error(res.message ?? "没取到这位歌手的资料");
      return;
    }
    applyProfile(res.data);
  } catch (error) {
    const detail = (error as any)?.response?.data?.message ?? "";
    ElMessage.error(`获取资料失败${detail ? "：" + detail : ""}`);
  } finally {
    qqLoadingMid.value = "";
  }
}

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
  const filled: string[] = [];
  const kept: string[] = [];

  // 名字总是用选中的那位覆盖：用户点这个候选就是为了换人
  if (profile.artistName) {
    form.artistName = profile.artistName;
    filled.push("歌手名");
  }

  if (profile.gender != null) {
    form.gender = profile.gender;
    filled.push("类型");
  }

  if (profile.birth) {
    form.birth = profile.birth as any;
    filled.push("生日");
  }

  if (profile.area) {
    if (!form.area?.trim()) {
      form.area = profile.area;
      filled.push("国籍");
    } else {
      kept.push("国籍");
    }
  }

  if (profile.introduction) {
    if (!form.introduction?.trim()) {
      form.introduction = profile.introduction;
      filled.push("简介");
    } else {
      kept.push("简介");
    }
  }

  // 头像：只有对方确实有图才接管预览。地址能拼出来不代表图存在。
  if (profile.hasAvatar && profile.avatarUrls?.["500"]) {
    clearAvatar();
    form.avatarPreview = profile.avatarUrls["500"];
    form.qqSingerMid = profile.singerMid;
    filled.push("头像");
  }

  lastFilledFields.value = filled;

  const parts: string[] = [];
  if (filled.length) {
    parts.push(`已填充：${filled.join("、")}`);
  }
  if (kept.length) {
    parts.push(`保留你已填的：${kept.join("、")}`);
  }
  if (parts.length) {
    ElMessage.success(parts.join("；"));
  } else {
    ElMessage.info("这位歌手没有可填充的资料");
  }
}

/** 清掉「来自 QQ」的状态，恢复成完全手填。 */
function clearQqSelection() {
  const form = newFormInline.value;
  form.qqSingerMid = "";
  if (form.avatarPreview && !form.avatarFile) {
    // 当前预览来自 QQ，不是用户上传的文件
    form.avatarPreview = "";
  }
  lastFilledFields.value = [];
}

/**
 * 选图后只做本地预览，不立即上传 —— 用户还可能改名字、甚至取消整个新增。
 * 真正上传发生在点「确定」时，由 addArtistWithAvatar 一并提交。
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
  if (newFormInline.value.avatarPreview) {
    URL.revokeObjectURL(newFormInline.value.avatarPreview);
  }
  newFormInline.value.avatarFile = file;
  newFormInline.value.avatarPreview = URL.createObjectURL(file);
  // 自己上传了图，就不再走 QQ 下载
  newFormInline.value.qqSingerMid = "";
}

function clearAvatar() {
  if (newFormInline.value.avatarPreview?.startsWith("blob:")) {
    URL.revokeObjectURL(newFormInline.value.avatarPreview);
  }
  newFormInline.value.avatarFile = null;
  newFormInline.value.avatarPreview = "";
  newFormInline.value.qqSingerMid = "";
}

function getRef() {
  return ruleFormRef.value;
}

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
        从 QQ 音乐搜索并自动填充。
        刻意做成「搜 → 选 → 填」三步而不是「输入名字就自动填」：
        实测有 47 位歌手搜出 2 个候选，且第一条不一定对（LANY 的第一条是「Blue」，
        Christopher 的第一条是「黄凯芹」），必须由人确认是哪一位。
      -->
      <re-col v-if="isCreate" :value="24" :xs="24" :sm="24">
        <el-form-item label="自动填充">
          <div class="w-full">
            <div class="flex items-center gap-2">
              <el-input
                v-model="qqKeyword"
                placeholder="输入歌手名，从 QQ 音乐搜索资料"
                clearable
                class="!w-[260px]"
                @keyup.enter="handleQqSearch"
              />
              <el-button
                type="primary"
                :loading="qqSearching"
                @click="handleQqSearch"
              >
                搜索
              </el-button>
              <span
                v-if="newFormInline.qqSingerMid"
                class="text-xs text-[var(--el-color-success)]"
              >
                已选用 QQ 候选，头像将由服务端下载
              </span>
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
                  newFormInline.qqSingerMid === item.singerMid
                    ? 'border-[var(--el-color-primary)] bg-[var(--el-color-primary-light-9)]'
                    : 'border-[var(--el-border-color)] hover:border-[var(--el-color-primary-light-5)]'
                "
                @click="handleSelectCandidate(item)"
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
                    <el-tag
                      v-if="item.existingInLibrary"
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
                  </div>
                </div>
                <el-icon
                  v-if="qqLoadingMid === item.singerMid"
                  class="is-loading shrink-0"
                >
                  <IconifyIconOffline icon="ri:loader-4-line" />
                </el-icon>
              </div>
            </div>

            <div
              v-else-if="qqSearched && !qqSearching"
              class="mt-2 text-xs text-[var(--el-text-color-secondary)]"
            >
              没搜到候选。可以直接手填下面的资料，或换个关键词再试。
            </div>

            <div
              v-if="newFormInline.qqSingerMid"
              class="mt-2 text-xs text-[var(--el-text-color-secondary)]"
            >
              填充只影响表单，不会自动保存；已填好的字段不会被覆盖。
              <el-button link type="danger" size="small" @click="clearQqSelection">
                取消 QQ 填充
              </el-button>
            </div>
          </div>
        </el-form-item>
      </re-col>

      <!-- 头像只在新增时可选：修改模式的头像走列表里的「上传头像」，那里带裁剪 -->
      <re-col v-if="isCreate" :value="24" :xs="24" :sm="24">
        <el-form-item label="歌手头像">
          <div class="flex items-center gap-4">
            <el-image
              v-if="newFormInline.avatarPreview"
              :src="newFormInline.avatarPreview"
              fit="cover"
              class="w-[72px] h-[72px] rounded-full border border-[var(--el-border-color)]"
            />
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
                    {{
                      newFormInline.avatarPreview ? "更换图片" : "选择图片"
                    }}
                  </el-button>
                </el-upload>
                <el-button
                  v-if="newFormInline.avatarPreview"
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
