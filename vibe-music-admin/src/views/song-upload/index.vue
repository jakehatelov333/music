<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { UploadFile } from "element-plus";
import {
  CircleCheckFilled,
  Document,
  Files,
  Headset,
  InfoFilled,
  List,
  Loading,
  Search,
  Timer,
  UploadFilled
} from "@element-plus/icons-vue";
import defaultCover from "@/assets/song.jpg";
import { message } from "@/utils/message";
import { getArtistList } from "@/api/system";
import { searchLyricsByMeta, type LyricsSearchResult } from "@/api/lyrics";
import {
  clearSongUpload,
  commitSongUpload,
  prepareSongUpload,
  updateSongUploadAssets,
  type ArtistParsing,
  type SongUploadFiles,
  type SongUploadMetadata,
  type SongUploadPrepareResult
} from "@/api/songUpload";

defineOptions({
  name: "SongUpload"
});

const AUDIO_EXTENSIONS = [".mp3", ".flac", ".wav"];
const COVER_EXTENSIONS = [".jpg", ".jpeg", ".png"];
const LYRICS_EXTENSIONS = [".lrc", ".txt"];
const STYLE_OPTIONS = [
  "节奏布鲁斯",
  "欧美流行",
  "华语流行",
  "粤语流行",
  "国风流行",
  "韩国流行",
  "日本流行",
  "嘻哈说唱",
  "非洲节拍",
  "原声带",
  "轻音乐",
  "摇滚",
  "朋克",
  "电子",
  "国风",
  "乡村",
  "古典"
];

const audioUploadRef = ref();
const coverUploadRef = ref();
const lyricsUploadRef = ref();
const preparing = ref(false);
const assetsUploading = ref(false);
const committing = ref(false);
// 歌词搜索：上传阶段歌曲尚未入库、没有 songId，改按表单元数据走 /admin/song/lyrics/search。
const lyricsSearching = ref(false);
const lyricsSearchResult = ref<LyricsSearchResult | null>(null);
const prepared = ref(false);
const uploadId = ref("");
const metadata = ref<SongUploadMetadata | null>(null);
const files = ref<SongUploadFiles | null>(null);
const warning = ref("");
const audioName = ref("");
const pendingAudioName = ref("");
const coverName = ref("");
const lyricsName = ref("");
const pendingCover = ref<File | null>(null);
const pendingLyrics = ref<File | null>(null);
const localCoverUrl = ref("");
const artistMatched = ref(false);
/** 后端对元数据歌手字段的解析结果（是否疑似合作歌手、哪些名称未匹配） */
const artistParsing = ref<ArtistParsing | null>(null);
/** 歌手下拉的候选（远程搜索填充；已选中的歌手也会被并入，保证标签能显示名字） */
const artistOptions = ref<{ artistId: number; artistName: string }[]>([]);
const artistSearching = ref(false);

const form = reactive({
  songName: "",
  artistName: "",
  artistId: null as number | null,
  /**
   * 演唱歌手 id 列表，顺序即展示顺序（第一个为主艺人）。
   * 非空时提交走 artistIds；为空时回退到旧的「按名字自动创建单歌手」路径。
   */
  artistIds: [] as number[],
  album: "",
  releaseDate: undefined as string | undefined,
  style: [] as string[],
  lyrics: ""
});

const selectedArtistNames = computed(() =>
  form.artistIds
    .map(id => artistOptions.value.find(item => item.artistId === id)?.artistName)
    .filter(Boolean)
    .join(" / ")
);

const hasUnmatchedCandidate = computed(() =>
  (artistParsing.value?.candidates || []).some(item => !item.matched)
);

const artistParsingTitle = computed(() => {
  switch (artistParsing.value?.strategy) {
    case "SPLIT_CONFIRMED":
      return "已识别为合作演唱，可直接拆分";
    case "SPLIT_CANDIDATE":
      return "疑似合作演唱，但存在未匹配的歌手，请人工确认";
    case "UNRESOLVED":
      return "未能识别歌手，请人工确认";
    default:
      return "歌手解析结果";
  }
});

const coverPreview = computed(
  () => localCoverUrl.value || metadata.value?.coverUrl || defaultCover
);
const durationText = computed(
  () => metadata.value?.durationText || "未解析到时长"
);
const fileSizeText = computed(() => formatSize(metadata.value?.fileSize));
const activeStep = computed(() => (prepared.value ? 1 : 0));
const canCommit = computed(
  () =>
    prepared.value &&
    !preparing.value &&
    !assetsUploading.value &&
    !committing.value
);

function handleAudioChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!hasExtension(raw.name, AUDIO_EXTENSIONS)) {
    message("仅支持 MP3、FLAC、WAV 格式的音频文件", { type: "warning" });
    audioUploadRef.value?.clearFiles();
    return;
  }
  pendingAudioName.value = raw.name;
  prepareAudio(raw);
}

async function prepareAudio(audio: File) {
  preparing.value = true;
  try {
    const res = await prepareSongUpload(audio);
    if (res.code !== 0 || !res.data) {
      throw new Error(res.message || "音频解析失败");
    }
    applyPreparedResult(res.data, true);
    audioName.value = pendingAudioName.value;
    message("音频上传成功，已自动解析歌曲信息", { type: "success" });
    if (pendingCover.value || pendingLyrics.value) {
      await uploadAssets(pendingCover.value, pendingLyrics.value);
    }
  } catch (error: any) {
    message(error?.message || "音频上传失败，请重试", { type: "error" });
    resetState();
  } finally {
    preparing.value = false;
  }
}

function handleCoverChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!hasExtension(raw.name, COVER_EXTENSIONS)) {
    message("封面仅支持 JPG、JPEG、PNG 格式", { type: "warning" });
    coverUploadRef.value?.clearFiles();
    return;
  }
  coverName.value = raw.name;
  setLocalCover(raw);
  if (prepared.value) {
    uploadAssets(raw, null);
  } else {
    pendingCover.value = raw;
    message("封面已选择，将在音频解析完成后上传", { type: "info" });
  }
}

function handleLyricsChange(uploadFile: UploadFile) {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!hasExtension(raw.name, LYRICS_EXTENSIONS)) {
    message("歌词仅支持 LRC、TXT 格式", { type: "warning" });
    lyricsUploadRef.value?.clearFiles();
    return;
  }
  lyricsName.value = raw.name;
  if (prepared.value) {
    uploadAssets(null, raw);
  } else {
    pendingLyrics.value = raw;
    message("歌词已选择，将在音频解析完成后上传", { type: "info" });
  }
}

/**
 * 按当前表单的歌名 / 歌手 / 专辑 / 时长搜索歌词。
 *
 * 复用后端已有的歌词补全链路（QQ 音乐主源 + LRCLIB 兜底 + 匹配打分阈值），
 * 只是把入口从「按 songId」换成「按元数据」——上传阶段歌曲还没入库，没有 songId。
 */
async function handleSearchLyrics() {
  if (!form.songName.trim()) {
    message("请先填写歌曲名", { type: "warning" });
    return;
  }
  lyricsSearching.value = true;
  lyricsSearchResult.value = null;
  try {
    const res = await searchLyricsByMeta({
      songName: form.songName.trim(),
      artistName: selectedArtistNames.value || form.artistName || null,
      album: form.album || null,
      durationSeconds: metadata.value?.duration ?? null
    });
    if (res.code !== 0) {
      message(res.message || "歌词搜索失败", { type: "error" });
      return;
    }
    lyricsSearchResult.value = res.data ?? null;
    if (!res.data?.matched) {
      message(res.data?.message || "未找到匹配的歌词", { type: "info" });
    }
  } catch (error) {
    message("歌词搜索失败，请稍后重试", { type: "error" });
  } finally {
    lyricsSearching.value = false;
  }
}

/** 把搜到的歌词填入编辑框（仍可手动修改，提交时才入库）。 */
function applySearchedLyrics() {
  const lyric = lyricsSearchResult.value?.lyric;
  if (!lyric) return;
  form.lyrics = lyric;
  lyricsSearchResult.value = null;
  message("歌词已填入，可继续编辑后保存", { type: "success" });
}

async function uploadAssets(cover?: File | null, lyrics?: File | null) {
  if (!uploadId.value || (!cover && !lyrics)) return;
  assetsUploading.value = true;
  try {
    const res = await updateSongUploadAssets(
      uploadId.value,
      cover || undefined,
      lyrics || undefined
    );
    if (res.code !== 0 || !res.data) {
      throw new Error(res.message || "文件上传失败");
    }
    applyPreparedResult(res.data, false);
    if (cover) {
      pendingCover.value = null;
      clearLocalCover();
    }
    if (lyrics) {
      pendingLyrics.value = null;
    }
    message(cover ? "封面更新成功" : "歌词上传成功", { type: "success" });
  } catch (error: any) {
    message(error?.message || "文件上传失败，请重试", { type: "error" });
  } finally {
    assetsUploading.value = false;
  }
}

/** 把解析出的已匹配歌手并入下拉候选，保证多选标签能显示名字。 */
function mergeArtistOptions(items: { artistId: number; artistName: string }[]) {
  const merged = new Map(artistOptions.value.map(item => [item.artistId, item]));
  items.forEach(item => {
    if (item?.artistId) merged.set(item.artistId, item);
  });
  artistOptions.value = [...merged.values()];
}

async function loadArtistOptions(keyword?: string) {
  const trimmed = keyword?.trim();
  artistSearching.value = true;
  try {
    const res = await getArtistList({
      pageNum: 1,
      pageSize: 50,
      ...(trimmed ? { artistName: trimmed } : {})
    });
    const items = (res.data?.items || []).map((item: any) => ({
      artistId: item.artistId as number,
      artistName: item.artistName as string
    }));
    if (trimmed) {
      mergeArtistOptions(items);
    } else {
      artistOptions.value = items;
    }
  } catch {
    if (!trimmed) artistOptions.value = [];
  } finally {
    artistSearching.value = false;
  }
}

function applyPreparedResult(data: SongUploadPrepareResult, fillForm: boolean) {
  uploadId.value = data.uploadId;
  metadata.value = data.metadata;
  files.value = data.files;
  warning.value = data.warning || "";
  artistMatched.value = data.artistMatched;
  artistParsing.value = data.artistParsing || null;
  prepared.value = true;

  if (fillForm) {
    form.songName = data.metadata.title || "";
    form.album = data.metadata.album || "";
    form.releaseDate = data.metadata.year
      ? `${data.metadata.year}-01-01`
      : undefined;
    form.lyrics = data.files.lyricsContent || "";

    // 只有「完整匹配」或「每一段都唯一命中」才自动预填多选；
    // 其余情况（存在未匹配或歧义）一律留给管理员人工确认，不替用户猜。
    const parsed = data.artistParsing;
    const autoFilled =
      parsed?.strategy === "EXACT_MATCH" ||
      parsed?.strategy === "SPLIT_CONFIRMED"
        ? (parsed.candidates || [])
            .filter(candidate => candidate.matched && candidate.artistId)
            .map(candidate => ({
              artistId: candidate.artistId as number,
              artistName: candidate.artistName
            }))
        : [];

    if (autoFilled.length) {
      mergeArtistOptions(autoFilled);
      form.artistIds = autoFilled.map(item => item.artistId);
      form.artistName = "";
      form.artistId = autoFilled[0].artistId;
      artistMatched.value = true;
    } else {
      form.artistIds = [];
      form.artistName = data.matchedArtistName || data.metadata.artist || "";
      form.artistId = data.matchedArtistId ?? null;
    }
  }
}

function handleArtistInput(value: string) {
  form.artistName = value;
  form.artistId = null;
  artistMatched.value = false;
}

/** 多选变化：一旦选了系统歌手，就清掉旧的自由文本，避免两条提交路径并存。 */
function handleArtistIdsChange(ids: number[]) {
  if (ids.length) {
    form.artistName = "";
    form.artistId = ids[0];
    artistMatched.value = true;
  } else {
    artistMatched.value = false;
  }
}

function searchArtists(query: string) {
  return loadArtistOptions(query);
}

async function handleCommit() {
  if (!uploadId.value) {
    message("请先上传并解析音频文件", { type: "warning" });
    return;
  }
  if (!form.songName.trim()) {
    message("请填写歌曲名", { type: "warning" });
    return;
  }
  if (!form.artistIds.length && !form.artistName.trim()) {
    message("请选择或填写歌手", { type: "warning" });
    return;
  }

  if (!form.releaseDate) {
    message("请填写发行日期", { type: "warning" });
    return;
  }

  committing.value = true;
  try {
    const hasArtistIds = form.artistIds.length > 0;
    const res = await commitSongUpload({
      uploadId: uploadId.value,
      songName: form.songName.trim(),
      // artistIds 非空时优先；否则回退到旧的「按名字自动创建单歌手」路径。
      artistIds: hasArtistIds ? form.artistIds : null,
      artistId: hasArtistIds ? null : form.artistId,
      artistName: hasArtistIds ? null : form.artistName.trim(),
      album: form.album.trim(),
      releaseDate: form.releaseDate,
      style: form.style.length ? form.style.join(",") : null,
      lyrics: form.lyrics
    });
    if (res.code !== 0) {
      throw new Error(res.message || "歌曲保存失败");
    }
    message(
      res.data.artistCreated
        ? `《${res.data.songName}》上传成功，并已自动创建歌手 ${res.data.artistName}`
        : `《${res.data.songName}》上传成功`,
      { type: "success" }
    );
    resetState();
  } catch (error: any) {
    message(error?.message || "歌曲保存失败，请重试", { type: "error" });
  } finally {
    committing.value = false;
  }
}

async function handleClear() {
  if (!uploadId.value) {
    resetState();
    return;
  }
  try {
    const res = await clearSongUpload(uploadId.value);
    if (res.code !== 0) {
      throw new Error(res.message || "清空失败");
    }
    message("临时文件已清空", { type: "success" });
    resetState();
  } catch (error: any) {
    message(error?.message || "清空失败，请重试", { type: "error" });
  }
}

function resetState() {
  uploadId.value = "";
  prepared.value = false;
  metadata.value = null;
  files.value = null;
  warning.value = "";
  audioName.value = "";
  pendingAudioName.value = "";
  coverName.value = "";
  lyricsName.value = "";
  pendingCover.value = null;
  pendingLyrics.value = null;
  artistMatched.value = false;
  artistParsing.value = null;
  clearLocalCover();
  form.songName = "";
  form.artistName = "";
  form.artistId = null;
  form.artistIds = [];
  form.album = "";
  form.releaseDate = undefined;
  form.style = [];
  form.lyrics = "";
  audioUploadRef.value?.clearFiles();
  coverUploadRef.value?.clearFiles();
  lyricsUploadRef.value?.clearFiles();
}

function setLocalCover(file: File) {
  clearLocalCover();
  localCoverUrl.value = URL.createObjectURL(file);
}

function clearLocalCover() {
  if (localCoverUrl.value?.startsWith("blob:")) {
    URL.revokeObjectURL(localCoverUrl.value);
  }
  localCoverUrl.value = "";
}

function hasExtension(filename: string, extensions: string[]) {
  const lower = filename.toLowerCase();
  return extensions.some(extension => lower.endsWith(extension));
}

function formatSize(size?: number | null) {
  if (!size) return "-";
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

onBeforeUnmount(clearLocalCover);

// 预载一页歌手，让多选下拉一打开就有候选（远程搜索会在此基础上收窄）。
onMounted(() => {
  loadArtistOptions();
});
</script>

<template>
  <div class="song-upload-page">
    <section class="hero-card">
      <div class="hero-icon">
        <IconifyIconOnline icon="ri:upload-cloud-2-line" width="36" />
      </div>
      <div class="hero-copy">
        <h1>歌曲上传</h1>
        <p>支持 MP3、FLAC、WAV 格式，系统自动解析音频信息并提取内嵌封面。</p>
      </div>
      <el-steps :active="activeStep" finish-status="success" class="hero-steps">
        <el-step title="上传文件" description="选择音频与可选资源" />
        <el-step title="核对信息" description="自动解析后可修改" />
        <el-step title="上传并保存" description="正式写入歌曲库" />
      </el-steps>
    </section>

    <div class="content-grid">
      <main class="main-column">
        <section class="panel">
          <div class="panel-heading">
            <span class="step-badge">1</span>
            <div>
              <h2>上传文件</h2>
              <p>
                选择音频后会立即上传到临时目录并开始解析，不会创建正式歌曲。
              </p>
            </div>
          </div>

          <div class="upload-grid">
            <el-upload
              ref="audioUploadRef"
              class="upload-item audio-upload"
              drag
              action="#"
              accept=".mp3,.flac,.wav"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleAudioChange"
            >
              <div class="upload-content">
                <IconifyIconOnline icon="ri:music-2-line" width="32" />
                <strong>拖拽音频到这里</strong>
                <span>或点击选择 MP3 / FLAC / WAV</span>
                <em v-if="audioName">{{ audioName }}</em>
              </div>
            </el-upload>

            <el-upload
              ref="coverUploadRef"
              class="upload-item"
              action="#"
              accept=".jpg,.jpeg,.png"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleCoverChange"
            >
              <div class="upload-content compact">
                <IconifyIconOnline icon="ri:image-add-line" width="28" />
                <strong>上传封面</strong>
                <span>可选，JPG / PNG</span>
                <em v-if="coverName">{{ coverName }}</em>
              </div>
            </el-upload>

            <el-upload
              ref="lyricsUploadRef"
              class="upload-item"
              action="#"
              accept=".lrc,.txt"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleLyricsChange"
            >
              <div class="upload-content compact">
                <IconifyIconOnline icon="ri:file-text-line" width="28" />
                <strong>上传歌词</strong>
                <span>可选，LRC / TXT</span>
                <em v-if="lyricsName">{{ lyricsName }}</em>
              </div>
            </el-upload>
          </div>

          <div v-if="preparing" class="process-tip">
            <el-icon class="is-loading"><Loading /></el-icon>
            正在上传并解析音频信息，请稍候...
          </div>
          <div v-else-if="prepared" class="process-tip success">
            <el-icon><CircleCheckFilled /></el-icon>
            文件上传成功，已生成下方歌曲信息
          </div>
        </section>

        <section v-if="prepared" class="panel">
          <div class="panel-heading">
            <span class="step-badge">2</span>
            <div>
              <h2>核对歌曲信息</h2>
              <p>信息已根据音频标签和文件名自动填充，请检查后修改。</p>
            </div>
          </div>

          <el-alert
            v-if="warning"
            :title="warning"
            type="warning"
            :closable="false"
            show-icon
            class="mb-4"
          />

          <div class="info-layout">
            <div class="cover-column">
              <el-image
                :src="coverPreview"
                fit="cover"
                class="cover-preview"
                :preview-src-list="[coverPreview]"
                preview-teleported
              />
              <el-upload
                action="#"
                accept=".jpg,.jpeg,.png"
                :auto-upload="false"
                :show-file-list="false"
                :on-change="handleCoverChange"
              >
                <el-button :loading="assetsUploading" plain>
                  更换封面
                </el-button>
              </el-upload>
              <div class="cover-source">
                {{
                  metadata?.coverSource === "EMBEDDED"
                    ? "来自 MP3 内嵌封面"
                    : metadata?.coverSource === "UPLOADED"
                      ? "使用独立上传封面"
                      : "未检测到封面，将使用默认图片"
                }}
              </div>
            </div>

            <el-form :model="form" label-position="top" class="info-form">
              <el-row :gutter="18">
                <el-col :xs="24" :md="12">
                  <el-form-item label="歌曲名" required>
                    <el-input
                      v-model="form.songName"
                      maxlength="50"
                      show-word-limit
                      placeholder="请输入歌曲名"
                    />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="歌手" required>
                    <div class="artist-field">
                      <el-select
                        v-model="form.artistIds"
                        multiple
                        filterable
                        remote
                        reserve-keyword
                        :remote-method="searchArtists"
                        :loading="artistSearching"
                        placeholder="搜索并选择歌手，可多选；顺序即展示顺序"
                        class="w-full"
                        @change="handleArtistIdsChange"
                      >
                        <el-option
                          v-for="item in artistOptions"
                          :key="item.artistId"
                          :label="item.artistName"
                          :value="item.artistId"
                        >
                          <div class="artist-option">
                            <span>{{ item.artistName }}</span>
                            <small>ID: {{ item.artistId }}</small>
                          </div>
                        </el-option>
                      </el-select>

                      <!-- 未选系统歌手时保留旧的「按名字自动创建」路径（仅单歌手） -->
                      <el-input
                        v-if="!form.artistIds.length"
                        v-model="form.artistName"
                        clearable
                        placeholder="或直接输入歌手名，保存时自动创建（仅单歌手）"
                        @input="handleArtistInput"
                      />

                      <el-tag
                        v-if="form.artistIds.length"
                        type="success"
                        effect="light"
                      >
                        已选 {{ form.artistIds.length }} 位：{{ selectedArtistNames }}
                      </el-tag>
                      <el-tag
                        v-else-if="form.artistName"
                        type="warning"
                        effect="light"
                      >
                        未匹配，保存时将自动创建歌手
                      </el-tag>
                      <el-tag v-else type="info" effect="light">
                        请选择或输入歌手
                      </el-tag>
                    </div>
                  </el-form-item>
                </el-col>

                <el-col
                  v-if="artistParsing && artistParsing.strategy !== 'EXACT_MATCH'"
                  :span="24"
                >
                  <el-alert
                    :title="artistParsingTitle"
                    :type="
                      artistParsing.strategy === 'SPLIT_CONFIRMED'
                        ? 'success'
                        : 'warning'
                    "
                    :closable="false"
                    show-icon
                  >
                    <div class="artist-parsing">
                      <span class="artist-parsing__raw">
                        原始文本：{{ artistParsing.raw || "（空）" }}
                      </span>
                      <div class="artist-parsing__items">
                        <el-tag
                          v-for="(candidate, index) in artistParsing.candidates"
                          :key="index"
                          :type="candidate.matched ? 'success' : 'danger'"
                          size="small"
                          effect="light"
                        >
                          {{ candidate.artistName
                          }}{{
                            candidate.matched
                              ? `（ID: ${candidate.artistId}）`
                              : " — 未匹配"
                          }}
                        </el-tag>
                      </div>
                      <p v-if="hasUnmatchedCandidate" class="artist-parsing__hint">
                        未匹配的歌手不会被自动创建。请先到「歌手管理」中创建，
                        再回到这里选择；也可以忽略提示，把整段文本当作一位歌手。
                      </p>
                    </div>
                  </el-alert>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="专辑">
                    <el-input
                      v-model="form.album"
                      placeholder="可选，请输入专辑"
                    />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="发行日期">
                    <el-date-picker
                      v-model="form.releaseDate"
                      type="date"
                      value-format="YYYY-MM-DD"
                      placeholder="请选择发行日期"
                      :disabled-date="date => date.getTime() > Date.now()"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="歌曲类型">
                    <el-select
                      v-model="form.style"
                      multiple
                      collapse-tags
                      clearable
                      placeholder="请选择歌曲类型"
                      class="w-full"
                    >
                      <el-option
                        v-for="item in STYLE_OPTIONS"
                        :key="item"
                        :label="item"
                        :value="item"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :xs="24" :md="12">
                  <el-form-item label="音频时长">
                    <el-input :model-value="durationText" readonly>
                      <template #prefix>
                        <el-icon><Timer /></el-icon>
                      </template>
                    </el-input>
                  </el-form-item>
                </el-col>
              </el-row>
            </el-form>
          </div>

          <div class="audio-meta">
            <div class="meta-title">
              <el-icon><Headset /></el-icon>
              音频信息（自动解析）
            </div>
            <div class="meta-grid">
              <span
                >格式<strong>{{ metadata?.audioFormat || "-" }}</strong></span
              >
              <span
                >文件大小<strong>{{ fileSizeText }}</strong></span
              >
              <span
                >采样率<strong>{{
                  metadata?.sampleRate ? `${metadata.sampleRate} Hz` : "-"
                }}</strong></span
              >
              <span
                >比特率<strong>{{
                  metadata?.bitRate ? `${metadata.bitRate} bps` : "-"
                }}</strong></span
              >
            </div>
          </div>
        </section>

        <section v-if="prepared" class="panel">
          <div class="panel-heading">
            <span class="step-badge">3</span>
            <div>
              <h2>歌词预览与编辑</h2>
              <p>
                歌词可选，上传 LRC/TXT
                后会直接显示原文，也可以在这里粘贴或修改。
              </p>
            </div>
            <el-upload
              action="#"
              accept=".lrc,.txt"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleLyricsChange"
              class="heading-action"
            >
              <el-button :loading="assetsUploading" plain
                >上传歌词文件</el-button
              >
            </el-upload>
          </div>

          <!-- 歌词搜索：复用后端歌词补全链路，按表单的歌名/歌手/专辑/时长检索 -->
          <div class="lyrics-search-bar">
            <el-button
              :loading="lyricsSearching"
              plain
              @click="handleSearchLyrics"
            >
              <el-icon class="mr-1"><Search /></el-icon>
              搜索歌词
            </el-button>
            <span class="lyrics-search-hint">
              按上方歌名、歌手、专辑与音频时长匹配，命中后填入下方编辑框
            </span>
          </div>

          <el-alert
            v-if="lyricsSearchResult"
            :type="lyricsSearchResult.matched ? 'success' : 'info'"
            :closable="true"
            show-icon
            class="lyrics-search-result"
            @close="lyricsSearchResult = null"
          >
            <template #title>
              <template v-if="lyricsSearchResult.matched">
                命中歌词（来源 {{ lyricsSearchResult.source || "未知" }}<template
                  v-if="lyricsSearchResult.score"
                  >，匹配度
                  {{ Math.round(lyricsSearchResult.score * 100) }}%</template
                >）
              </template>
              <template v-else>
                {{ lyricsSearchResult.message || "未找到匹配的歌词" }}
              </template>
            </template>
            <template v-if="lyricsSearchResult.matched">
              <div class="lyrics-search-preview">
                {{ (lyricsSearchResult.lyric || "").slice(0, 200) }}<template
                  v-if="(lyricsSearchResult.lyric || '').length > 200"
                  >…</template
                >
              </div>
              <el-button
                type="primary"
                size="small"
                class="mt-2"
                @click="applySearchedLyrics"
              >
                填入编辑框
              </el-button>
            </template>
          </el-alert>
          <el-input
            v-model="form.lyrics"
            type="textarea"
            :rows="8"
            resize="vertical"
            placeholder="可选：粘贴 LRC 或普通文本歌词"
          />
        </section>

        <div v-if="prepared" class="bottom-actions">
          <el-button plain :disabled="committing" @click="handleClear">
            清空
          </el-button>
          <el-button
            type="primary"
            size="large"
            :loading="committing"
            :disabled="!canCommit"
            @click="handleCommit"
          >
            <el-icon class="mr-2"><UploadFilled /></el-icon>
            上传并保存
          </el-button>
        </div>
      </main>

      <aside class="side-column">
        <section class="panel side-panel">
          <div class="side-title">
            <el-icon><List /></el-icon>
            <h3>上传状态</h3>
          </div>
          <div class="status-list">
            <div :class="{ done: !!audioName, active: preparing }">
              <el-icon :class="{ 'is-loading': preparing }">
                <Loading v-if="preparing" />
                <CircleCheckFilled v-else />
              </el-icon>
              <span>音频文件</span>
              <em>{{
                preparing ? "上传中..." : audioName ? "已上传" : "等待上传"
              }}</em>
            </div>
            <div :class="{ done: !!metadata?.coverUrl }">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>封面文件</span>
              <em>{{ metadata?.coverUrl ? "已就绪" : "可选" }}</em>
            </div>
            <div :class="{ done: !!form.lyrics }">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>歌词文件</span>
              <em>{{ form.lyrics ? "已就绪" : "可选" }}</em>
            </div>
            <div :class="{ active: preparing, done: prepared }">
              <el-icon :class="{ 'is-loading': preparing }">
                <Loading v-if="preparing" />
                <CircleCheckFilled v-else />
              </el-icon>
              <span>解析音频信息</span>
              <em>{{
                preparing ? "进行中..." : prepared ? "已完成" : "等待中..."
              }}</em>
            </div>
            <div :class="{ done: committing }">
              <el-icon><CircleCheckFilled /></el-icon>
              <span>保存歌曲信息</span>
              <em>{{ committing ? "保存中..." : "等待提交" }}</em>
            </div>
          </div>
        </section>

        <section v-if="prepared" class="panel side-panel">
          <div class="side-title">
            <el-icon><Headset /></el-icon>
            <h3>歌曲信息预览</h3>
          </div>
          <div class="song-preview">
            <img :src="coverPreview" alt="歌曲封面" />
            <div>
              <strong>{{ form.songName || "未填写歌曲名" }}</strong>
              <span>{{ form.artistName || "未匹配歌手" }}</span>
              <div class="preview-tags">
                <el-tag v-if="form.style[0]" size="small">{{
                  form.style[0]
                }}</el-tag>
                <el-tag v-if="form.releaseDate" size="small" type="info">{{
                  form.releaseDate
                }}</el-tag>
              </div>
            </div>
          </div>
          <div class="preview-stats">
            <span
              ><el-icon><Timer /></el-icon>{{ durationText }}</span
            >
            <span
              ><el-icon><Files /></el-icon>{{ fileSizeText }}</span
            >
          </div>
        </section>

        <section v-if="prepared" class="panel side-panel lyrics-preview">
          <div class="side-title">
            <el-icon><Document /></el-icon>
            <h3>歌词预览</h3>
          </div>
          <pre>{{ form.lyrics || "暂无歌词，可上传或直接输入。" }}</pre>
        </section>

        <section class="panel side-panel tips-panel">
          <div class="side-title">
            <el-icon><InfoFilled /></el-icon>
            <h3>小提示</h3>
          </div>
          <ol>
            <li>音频时长由后端解析，管理员不能手动填写。</li>
            <li>未匹配到系统歌手时，保存时会按当前名称自动创建歌手。</li>
            <li>封面优先使用内嵌封面，也可在解析后替换。</li>
            <li>关闭页面不会创建正式歌曲，临时文件 24 小时后自动清理。</li>
          </ol>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.song-upload-page {
  min-height: calc(100vh - 120px);
  color: var(--el-text-color-primary);
}

.hero-card,
.panel {
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgb(17 24 39 / 4%);
}

.hero-card {
  display: flex;
  gap: 18px;
  align-items: center;
  padding: 22px 24px;
  margin: 20px 20px 14px;
  background: linear-gradient(
      135deg,
      rgb(239 246 255 / 95%),
      rgb(255 255 255 / 98%)
    ),
    var(--el-bg-color-overlay);
}

.hero-icon {
  display: grid;
  flex: 0 0 auto;
  width: 62px;
  height: 62px;
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #1d4ed8);
  border-radius: 16px;
  place-items: center;
  box-shadow: 0 12px 24px rgb(37 99 235 / 24%);
}

.hero-copy {
  min-width: 240px;

  h1 {
    margin: 0 0 5px;
    font-size: 26px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }
}

.hero-steps {
  flex: 1;
  min-width: 0;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(280px, 23vw, 340px);
  gap: 14px;
  padding: 0 20px 24px;
}

.main-column,
.side-column {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.panel {
  padding: 20px;
  isolation: isolate;
}

.panel-heading {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.panel-heading h2,
.side-title h3 {
  margin: 0;
  font-size: 17px;
}

.panel-heading p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.step-badge {
  display: grid;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  font-weight: 700;
  color: #fff;
  background: #2563eb;
  border-radius: 50%;
  place-items: center;
}

.upload-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

.upload-item {
  width: 100%;
  min-width: 0;
  overflow: hidden;

  :deep(.el-upload) {
    display: block;
    width: 100%;
    min-width: 0;
  }

  :deep(.el-upload-dragger) {
    width: 100%;
    min-width: 0;
    height: auto;
    min-height: 154px;
    padding: 20px;
    overflow: hidden;
    border-color: var(--el-border-color);
    border-radius: 12px;
    transition: 0.2s ease;
  }

  :deep(.el-upload-dragger:hover) {
    border-color: #3b82f6;
    box-shadow: 0 8px 22px rgb(59 130 246 / 10%);
  }
}
.audio-upload :deep(.el-upload-dragger) {
  background: linear-gradient(
    135deg,
    rgb(239 246 255 / 90%),
    rgb(255 255 255 / 60%)
  );
}

.upload-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  color: #2563eb;

  strong {
    color: var(--el-text-color-primary);
  }

  span {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  em {
    display: -webkit-box;
    width: 100%;
    max-width: 100%;
    overflow: hidden;
    font-size: 12px;
    line-height: 1.35;
    color: #2563eb;
    overflow-wrap: anywhere;
    white-space: normal;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}
.upload-content.compact {
  color: #0f766e;
}

.process-tip {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 14px;
  margin-top: 14px;
  color: #2563eb;
  background: #eff6ff;
  border-radius: 10px;
}

.process-tip.success {
  color: #15803d;
  background: #f0fdf4;
}

.info-layout {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  gap: 28px;
}

.cover-column {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.cover-preview {
  width: 190px;
  height: 190px;
  border-radius: 12px;
  box-shadow: 0 12px 26px rgb(15 23 42 / 14%);
}

.cover-source {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.info-form {
  min-width: 0;
}

.artist-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.artist-option {
  display: flex;
  justify-content: space-between;
  width: 100%;

  small {
    color: var(--el-text-color-placeholder);
  }
}

.artist-parsing {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 4px;

  &__raw {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__items {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  &__hint {
    margin: 0;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }
}

.audio-meta {
  padding: 14px 16px;
  margin-top: 6px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.meta-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  font-weight: 600;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;

  span {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  strong {
    font-size: 14px;
    color: var(--el-text-color-primary);
  }
}

.heading-action {
  margin-left: auto;
}

/* 歌词搜索：与「上传歌词文件」并列的次要入口 */
.lyrics-search-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.lyrics-search-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.lyrics-search-result {
  margin-bottom: 10px;
}

.lyrics-search-preview {
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 6px;
}

.bottom-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 6px 2px 10px;
}

.side-panel {
  padding: 18px;
}

.side-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  color: #2563eb;
}

.side-title h3 {
  color: var(--el-text-color-primary);
}

.status-list {
  display: flex;
  flex-direction: column;
  gap: 15px;

  > div {
    display: grid;
    grid-template-columns: 20px minmax(0, 1fr) auto;
    gap: 8px;
    align-items: center;
    color: var(--el-text-color-secondary);
  }

  .el-icon {
    color: var(--el-text-color-placeholder);
  }

  .done .el-icon {
    color: #22c55e;
  }

  .active .el-icon {
    color: #3b82f6;
  }

  em {
    font-size: 12px;
    font-style: normal;
    color: var(--el-text-color-placeholder);
  }
}

.song-preview {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 14px;
  align-items: center;

  img {
    width: 92px;
    height: 92px;
    object-fit: cover;
    border-radius: 10px;
  }

  > div {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }

  strong,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--el-text-color-secondary);
  }
}

.preview-tags {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.preview-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding-top: 14px;
  margin-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);

  span {
    display: flex;
    gap: 6px;
    align-items: center;
    color: var(--el-text-color-secondary);
  }
}

.lyrics-preview pre {
  max-height: 240px;
  margin: 0;
  overflow: auto;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

.tips-panel ol {
  padding-left: 18px;
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

@media (max-width: 1400px) {
  .content-grid {
    grid-template-columns: minmax(0, 1fr) 300px;
  }

  .upload-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .audio-upload {
    grid-column: 1 / -1;
  }
}

@media (max-width: 1200px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .side-column {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

@media (max-width: 900px) {
  .hero-card {
    flex-wrap: wrap;
  }

  .hero-steps {
    order: 3;
    min-width: 100%;
  }

  .upload-grid,
  .info-layout,
  .meta-grid,
  .side-column {
    grid-template-columns: 1fr;
  }

  .cover-column {
    align-items: flex-start;
  }
}
</style>
