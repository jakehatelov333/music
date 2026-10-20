<script setup lang="ts">
import { computed, ref } from "vue";
import type { ArtistProfile } from "@/api/artistProfile";

/**
 * QQ 音乐歌手资料预览卡片。
 *
 * <p>存在的理由：搜索结果只是「名字 + 头像」，而管理员需要先看清
 * 生日、国籍、简介、代表作等再决定要不要覆盖表单里的内容。
 * 以前点一下候选就直接写进表单，等于「看不到就要做决定」。
 *
 * <p>本组件<b>不写表单</b>，只负责展示并通过 `apply` 事件把决定权交回父组件。
 *
 * <h3>为什么默认是收起的</h3>
 * 弹窗本身已经有 6 行表单，在 683px 高的视口里已经接近满屏。
 * 一张展开的预览卡片（简介 + 11 项 wiki）能再撑高 400px，
 * 结果就是「确定」按钮被顶到屏幕外 —— 预览本来是为了帮人做决定，
 * 却先把人要做决定的按钮藏起来了。所以：常驻信息保持一行高，
 * 长文本与 wiki 明细按需展开。
 */
const props = withDefaults(
  defineProps<{
    /** 要预览的资料；为空时整个卡片不渲染 */
    profile?: ArtistProfile | null;
    /** 是否是「当前正在编辑的这位歌手」 */
    isCurrentArtist?: boolean;
    /** 是否已经填进表单了 */
    applied?: boolean;
  }>(),
  { profile: null, isCurrentArtist: false, applied: false }
);

const emit = defineEmits<{ apply: [] }>();

const GENDER_LABEL: Record<number, string> = {
  0: "男歌手",
  1: "女歌手",
  2: "组合/乐队"
};

const genderLabel = computed(() => {
  const gender = props.profile?.gender;
  return gender == null ? "" : (GENDER_LABEL[gender] ?? "");
});

/**
 * wiki 里那些「不属于本系统字段、但管理员想看到」的键，按信息密度排序。
 *
 * <p>固定顺序而不是字典序：出生地、职业、出道日期、代表作品这几项几乎每位歌手都有，
 * 放前面才不用滚动。不在这张表里的键排在后面，仍然全部展示 ——
 * 万一 QQ 以后加了新字段，也不会因为前端不认识就吞掉。
 */
const EXTRA_ORDER = [
  "别名",
  "原名",
  "出生地",
  "职业",
  "出道日期",
  "代表作品",
  "代表作",
  "主要成就",
  "毕业院校",
  "血型",
  "星座",
  "粉丝名称",
  "应援色",
  "早年经历"
];

/** 已经作为独立字段展示过的 wiki 键，不再重复出现在「更多资料」里。 */
const HANDLED_KEYS = new Set(["中文名", "外文名", "国籍", "生日"]);

const extraFields = computed<Array<[string, string]>>(() => {
  const raw = props.profile?.extraFields;
  if (!raw) {
    return [];
  }
  const entries = Object.entries(raw).filter(
    ([key, value]) =>
      !HANDLED_KEYS.has(key) && typeof value === "string" && value.trim() !== ""
  );
  const rank = (key: string) => {
    const index = EXTRA_ORDER.indexOf(key);
    return index === -1 ? EXTRA_ORDER.length : index;
  };
  // Array.prototype.sort 在 V8 里是稳定排序，同 rank 的项保持 wiki 里的原始顺序
  return entries.sort((a, b) => rank(a[0]) - rank(b[0]));
});

const avatarUrl = computed(() => props.profile?.avatarUrls?.["500"] ?? "");

/** 简介是否长到需要折叠。两行约 60 字，超过才给「展开」。 */
const INTRO_PREVIEW = 60;
const introduction = computed(() => props.profile?.introduction?.trim() ?? "");
const introLong = computed(() => introduction.value.length > INTRO_PREVIEW);

/** 长文本折叠阈值：wiki 里的「主要成就」动辄上百字，不截断会撑爆卡片。 */
const LONG_TEXT = 120;

/** 截断超长文本，保留可读的开头。 */
function shorten(value: string): string {
  return value.length > LONG_TEXT ? value.slice(0, LONG_TEXT) + "…" : value;
}

/** 简介展开状态。换一位歌手时由父组件重建组件（key 变了），这里不用手动重置。 */
const introExpanded = ref(false);
/** wiki 明细展开状态。默认收起。 */
const extrasExpanded = ref(false);
</script>

<template>
  <div
    v-if="profile"
    class="rounded border border-[var(--el-border-color)] bg-[var(--el-fill-color-blank)] text-sm"
  >
    <!-- 常驻区：一眼看完的关键信息，高度固定，不滚动 -->
    <div class="flex items-start gap-3 p-3">
      <el-image
        v-if="profile.hasAvatar && avatarUrl"
        :src="avatarUrl"
        fit="cover"
        class="w-[48px] h-[48px] rounded-full shrink-0 border border-[var(--el-border-color)]"
      >
        <template #error>
          <div
            class="w-[48px] h-[48px] rounded-full bg-[var(--el-fill-color-light)] flex items-center justify-center text-xs text-[var(--el-text-color-placeholder)]"
          >
            无图
          </div>
        </template>
      </el-image>
      <div
        v-else
        class="w-[48px] h-[48px] rounded-full bg-[var(--el-fill-color-light)] flex items-center justify-center text-xs text-[var(--el-text-color-placeholder)] shrink-0"
      >
        无图
      </div>

      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2 flex-wrap">
          <span
            class="text-base font-medium truncate"
            :title="profile.artistName"
          >
            {{ profile.artistName }}
          </span>
          <el-tag v-if="genderLabel" size="small" effect="plain">
            {{ genderLabel }}
          </el-tag>
          <el-tag
            v-if="isCurrentArtist"
            size="small"
            type="primary"
            effect="plain"
          >
            当前歌手
          </el-tag>
          <el-tag v-if="applied" size="small" type="success" effect="dark">
            已填入
          </el-tag>
        </div>

        <div class="text-xs text-[var(--el-text-color-secondary)]">
          <span v-if="profile.foreignName">{{ profile.foreignName }} · </span>
          生日 {{ profile.birth || "未收录" }} · 国籍
          {{ profile.area || "未收录" }}
        </div>

        <!-- 简介：默认两行，长了才给展开 -->
        <div v-if="introduction" class="mt-1 text-xs leading-relaxed">
          <span class="text-[var(--el-text-color-secondary)]">简介：</span>
          <span
            :class="
              introExpanded || !introLong
                ? 'whitespace-pre-wrap break-words'
                : 'line-clamp-2'
            "
          >
            {{ introduction }}
          </span>
          <el-button
            v-if="introLong"
            link
            type="primary"
            size="small"
            @click="introExpanded = !introExpanded"
          >
            {{ introExpanded ? "收起" : "展开" }}
          </el-button>
        </div>

        <!-- wiki 明细：默认收起，只在展开时才占高度 -->
        <div v-if="extraFields.length" class="mt-1">
          <el-button
            link
            type="primary"
            size="small"
            @click="extrasExpanded = !extrasExpanded"
          >
            {{
              extrasExpanded
                ? "收起 QQ 更多资料"
                : `查看 QQ 更多资料（${extraFields.length} 项）`
            }}
          </el-button>
          <div
            v-if="extrasExpanded"
            class="mt-1 rounded bg-[var(--el-fill-color-light)] p-2 max-h-[160px] overflow-auto"
          >
            <div
              v-for="[key, value] in extraFields"
              :key="key"
              class="text-xs leading-relaxed flex gap-2 py-[2px]"
            >
              <span
                class="shrink-0 text-[var(--el-text-color-secondary)] w-[60px]"
              >
                {{ key }}
              </span>
              <span class="min-w-0 break-words">{{ shorten(value) }}</span>
            </div>
          </div>
        </div>
      </div>

      <el-button
        type="primary"
        size="small"
        class="shrink-0"
        @click="emit('apply')"
      >
        {{ applied ? "重新填充" : "填充到表单" }}
      </el-button>
    </div>

    <div
      v-if="applied"
      class="px-3 pb-2 -mt-1 text-xs text-[var(--el-color-success)]"
    >
      已填入下方表单，未保存
    </div>
  </div>
</template>
