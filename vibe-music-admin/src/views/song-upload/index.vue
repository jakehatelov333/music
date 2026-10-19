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
  suggestAlbums,
  type AlbumSuggestItem
} from "@/api/albumSuggest";
import {
  applyCoverFromAlbum,
  searchSongCovers,
  type CoverCandidate
} from "@/api/songCover";
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
// 封面搜索：自动模式用表单里的歌名/歌手；手动模式用管理员自己输入的关键词。
const coverSearching = ref(false);
const coverApplying = ref(false);
const coverCandidates = ref<CoverCandidate[]>([]);
const coverDialogVisible = ref(false);
const coverKeyword = ref("");
const coverSize = ref(500);
const coverSearched = ref(false);
/** 手动模式标记：true 表示本次结果来自用户输入的关键词而非自动推断 */
const coverManualMode = ref(false);
/** 当前已选中的专辑 mid，用于在候选网格里高亮 */
const selectedAlbumMid = ref("");

// 专辑联想：输入时防抖查询已有专辑，选中后绑定 albumId 精确归入
/** 下拉候选 */
const albumSuggestions = ref<AlbumSuggestItem[]>([]);
const albumSuggestLoading = ref(false);
/**
 * 已选中的专辑 id。
 *
 * 只要用户改动输入文字就必须清空 —— 否则会把歌曲塞进一个名字已经不匹配的专辑。
 * 这是本功能最容易出的 bug，所有改动 form.album 的路径都要清它。
 */
const albumId = ref<number | null>(null);
/** 已确认选中的专辑（用于展示「将归入已有专辑」） */
const selectedAlbum = ref<AlbumSuggestItem | null>(null);
/** 防抖定时器 */
let albumSuggestTimer: ReturnType<typeof setTimeout> | null = null;
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
/** el-select 实例引用，用于选中后清空筛选关键词 */
const artistSelectRef = ref();

/**
 * 一个歌手位的 token。
 *
 * <p>为什么用字符串 token 而不是「两个并行数组」：el-select 的 multiple 模式
 * 天然维护一个有序值数组，标签顺序就是用户选择顺序。把「已有歌手」与「待新建歌手」
 * 塞进同一个数组，顺序（谁排第一 = 谁是主艺人）由组件自己保证，不需要额外同步。
 *
 * <p>前缀区分来源：
 * <ul>
 *   <li>{@code id:142} —— 已存在的歌手，提交时进 artistIds</li>
 *   <li>{@code name:新歌手} —— 库里没有，提交时进 pendingArtistNames</li>
 * </ul>
 */
const ARTIST_TOKEN_ID = "id:";
const ARTIST_TOKEN_NAME = "name:";

const form = reactive({
  songName: "",
  /**
   * 已选歌手（有序 token）。第一个即主艺人。
   *
   * <p>取代了原先互斥的 artistIds + artistName 两条路径：现在一个选择器
   * 既能选已有歌手，也能输入新名字，两者可以混排。
   */
  artistTokens: [] as string[],
  album: "",
  releaseDate: undefined as string | undefined,
  style: [] as string[],
  lyrics: ""
});

/** 从 token 解析出歌手 id；非 id token 返回 null。 */
function artistIdOf(token: string): number | null {
  if (!token.startsWith(ARTIST_TOKEN_ID)) return null;
  const id = Number(token.slice(ARTIST_TOKEN_ID.length));
  return Number.isFinite(id) && id > 0 ? id : null;
}

/** 从 token 解析出歌手名（id token 需从候选中反查）。 */
function artistNameOf(token: string): string {
  if (token.startsWith(ARTIST_TOKEN_NAME)) {
    return token.slice(ARTIST_TOKEN_NAME.length);
  }
  const id = artistIdOf(token);
  if (id == null) return token;
  return artistOptions.value.find(item => item.artistId === id)?.artistName || `#${id}`;
}

/** 已选歌手 id 列表（有序）。 */
const selectedArtistIds = computed(() =>
  form.artistTokens.map(artistIdOf).filter((id): id is number => id != null)
);

/** 待新建的歌手名（有序）。 */
const pendingArtistNames = computed(() =>
  form.artistTokens
    .filter(token => token.startsWith(ARTIST_TOKEN_NAME))
    .map(token => token.slice(ARTIST_TOKEN_NAME.length).trim())
    .filter(Boolean)
);

/** 展示用：已选歌手名，按顺序。 */
const selectedArtistNames = computed(() =>
  form.artistTokens.map(artistNameOf).filter(Boolean).join(" / ")
);

/** 是否存在「待新建」的歌手（用于提示文案）。 */
const hasPendingArtists = computed(() => pendingArtistNames.value.length > 0);

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
      artistName: selectedArtistNames.value || null,
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

// ------------------------------------------------------------------ 封面搜索

/**
 * 打开封面搜索面板。
 *
 * 默认走自动模式：拿表单里的歌名/歌手/专辑与音频时长去搜。
 * 但元数据可能是错的（标签乱码、歌手填成文件名），所以面板里始终保留
 * 一个手动关键词输入框 —— 自动搜错了，管理员可以自己改词重搜。
 */
function openCoverSearch() {
  if (!prepared.value) {
    message("请先上传音频文件", { type: "warning" });
    return;
  }
  coverDialogVisible.value = true;
  // 已搜过就不自动重搜，避免每次打开都打一次外部接口
  if (!coverSearched.value) {
    searchCoversAuto();
  }
}

/** 自动模式：按表单元数据搜索。 */
async function searchCoversAuto() {
  if (!form.songName.trim()) {
    message("请先填写歌曲名，或在下方手动输入关键词", { type: "warning" });
    return;
  }
  coverKeyword.value = "";
  coverManualMode.value = false;
  await runCoverSearch({
    songName: form.songName.trim(),
    artistName: selectedArtistNames.value || null,
    album: form.album || null,
    durationSeconds: metadata.value?.duration ?? null
  });
}

/**
 * 手动模式：用管理员输入的关键词原样搜索。
 *
 * 后端在手动模式下不做任何歌名/歌手过滤 —— 因为元数据此时不可信，
 * 拿它过滤只会把正确结果筛掉。可以只填歌名，也可以只填专辑名。
 */
async function searchCoversManual() {
  const keyword = coverKeyword.value.trim();
  if (!keyword) {
    message("请输入歌名或专辑名", { type: "warning" });
    return;
  }
  coverManualMode.value = true;
  await runCoverSearch({ keyword });
}

async function runCoverSearch(payload: {
  keyword?: string;
  songName?: string;
  artistName?: string | null;
  album?: string | null;
  durationSeconds?: number | null;
}) {
  coverSearching.value = true;
  try {
    const res = await searchSongCovers(payload);
    if (res.code !== 0) {
      message(res.message || "封面搜索失败", { type: "error" });
      return;
    }
    coverCandidates.value = res.data ?? [];
    coverSearched.value = true;
    if (!coverCandidates.value.length) {
      message(
        coverManualMode.value
          ? "没有搜到带封面的专辑，换个关键词试试"
          : "没有找到匹配的封面，可以手动输入关键词重搜",
        { type: "info" }
      );
    }
  } catch {
    message("封面搜索失败，请稍后重试", { type: "error" });
  } finally {
    coverSearching.value = false;
  }
}

/** 取指定尺寸的封面地址；缺该尺寸时退回 500，再退回任意一档。 */
function coverUrlOf(candidate: CoverCandidate, size: number) {
  const urls = candidate.coverUrls || {};
  return urls[String(size)] || urls["500"] || Object.values(urls)[0] || "";
}

/**
 * 应用选中的封面：下载到当前上传会话的 staging。
 *
 * 这里才真正落盘 —— 候选图由浏览器直接加载 CDN，不经过后端，
 * 所以搜索阶段不会在 MinIO 里留下任何东西。
 */
async function applyCover(candidate: CoverCandidate) {
  if (!uploadId.value) {
    message("上传会话已失效，请重新上传音频", { type: "warning" });
    return;
  }
  coverApplying.value = true;
  selectedAlbumMid.value = candidate.albumMid;
  try {
    const res = await applyCoverFromAlbum(uploadId.value, {
      albumMid: candidate.albumMid,
      size: coverSize.value
    });
    if (res.code !== 0 || !res.data) {
      throw new Error(res.message || "封面应用失败");
    }
    applyPreparedResult(res.data, false);
    coverDialogVisible.value = false;
    coverName.value = "";
    clearLocalCover();
    message("封面已应用，保存后生效", { type: "success" });
  } catch (error: any) {
    message(error?.message || "封面应用失败，请重试", { type: "error" });
  } finally {
    coverApplying.value = false;
  }
}

/** 封面来源的展示文案。 */
const coverSourceText = computed(() => {
  switch (metadata.value?.coverSource) {
    case "EMBEDDED":
      return "来自 MP3 内嵌封面";
    case "UPLOADED":
      return "使用独立上传封面";
    case "SEARCHED":
      return "来自 QQ 音乐专辑封面";
    default:
      return "未检测到封面，将使用默认图片";
  }
});

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

/**
 * 把「已选中的歌手」并入候选，保证多选标签始终能显示名字。
 *
 * <p>为什么只并已选中的、而不是并全部历史结果：历史上这里合并的是
 * {@code artistOptions}（上一轮的候选）。于是搜索「周」时后端只返回 2 条，
 * 却被并进初始加载的 50 条里，下拉仍然显示 50 条、周杰伦排第 6 ——
 * 用户输入的关键词等于没生效。现在只保留「当前已选中」的那些，
 * 搜索结果本身原样替换。
 */
function keepSelectedArtists(items: { artistId: number; artistName: string }[]) {
  const byId = new Map(items.map(item => [item.artistId, item]));
  // 已选中但不在本次结果里的，补回去（否则标签会退化成只显示 id）
  selectedArtistIds.value.forEach(id => {
    if (byId.has(id)) return;
    const known = artistOptions.value.find(item => item.artistId === id);
    if (known) byId.set(id, known);
  });
  return [...byId.values()];
}

/**
 * 加载歌手候选。
 *
 * <p>用自增序号丢弃过期响应：远程搜索是逐字触发的，先发的请求可能后到，
 * 若无条件写入就会把「周」的结果覆盖成「z」的结果。
 */
let artistRequestSeq = 0;

async function loadArtistOptions(keyword?: string) {
  const trimmed = keyword?.trim();
  const seq = ++artistRequestSeq;
  artistSearching.value = true;
  try {
    const res = await getArtistList({
      pageNum: 1,
      pageSize: 50,
      ...(trimmed ? { artistName: trimmed } : {})
    });
    if (seq !== artistRequestSeq) {
      // 已有更新的请求发出，本次结果作废
      return;
    }
    const items = (res.data?.items || []).map((item: any) => ({
      artistId: item.artistId as number,
      artistName: item.artistName as string
    }));
    // 无论是否有关键词，都只保留「本次结果 + 已选中」，不再并入上一轮候选
    artistOptions.value = keepSelectedArtists(items);
  } catch {
    if (seq === artistRequestSeq && !trimmed) {
      artistOptions.value = [];
    }
  } finally {
    if (seq === artistRequestSeq) {
      artistSearching.value = false;
    }
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
    // 解析出来的专辑名只是文本，还没有对应的专辑实体，必须清空归属
    clearAlbumSelection();
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
      // 先把解析出的歌手并入候选，再赋 token —— 否则多选标签会因找不到 name 而显示成 id
      artistOptions.value = keepSelectedArtists([...autoFilled, ...artistOptions.value]);
      form.artistTokens = autoFilled.map(item => ARTIST_TOKEN_ID + item.artistId);
      artistMatched.value = true;
    } else {
      // 未匹配：把原始文本作为「待新建」预填进去，管理员可直接提交（走创建流程）
      // 或删掉它去搜索已有歌手。这里不替用户决定要不要建，只把文本原样呈现。
      const rawName = (data.matchedArtistName || data.metadata.artist || "").trim();
      form.artistTokens = rawName ? [ARTIST_TOKEN_NAME + rawName] : [];
      artistMatched.value = false;
    }
  }
}

/**
 * 歌手选择变化。
 *
 * <p>现在的选择器同时承载「选已有」和「输入新名字」，所以不再需要
 * 旧的 handleArtistInput（那个函数专门服务第二个输入框）。
 */
function handleArtistChange(tokens: string[]) {
  // allow-create 产生的值是「用户输入的裸字符串」，不带 name: 前缀。
  // 这里统一补上前缀，保证后续 artistIdOf / pendingArtistNames 的判定一致。
  const normalized = (tokens || []).map(token =>
    token.startsWith(ARTIST_TOKEN_ID) || token.startsWith(ARTIST_TOKEN_NAME)
      ? token
      : ARTIST_TOKEN_NAME + token.trim()
  );
  if (normalized.length !== (tokens || []).length
      || normalized.some((token, i) => token !== (tokens || [])[i])) {
    form.artistTokens = normalized;
  }
  artistMatched.value =
    normalized.length > 0 &&
    !normalized.some(token => token.startsWith(ARTIST_TOKEN_NAME));
  // 换歌手后原来的专辑归属可能不再成立（同名专辑属于不同歌手），
  // 必须清掉 albumId 与已确认选中态，让用户重新确认。
  clearAlbumSelection();
  // 选中后清空 el-select 的筛选词：否则搜索框里会残留上一个关键词，
  // 下次打开下拉时用户以为还在搜旧词，实际候选已经变了。
  artistSelectRef.value?.clearFilter?.();
  // 候选排序依赖 artistId（该歌手的专辑优先），换了歌手就重新查一次
  if (form.album.trim()) {
    scheduleAlbumSuggest(form.album);
  }
}

/** 删除某个歌手标签。 */
function removeArtistToken(index: number) {
  const next = [...form.artistTokens];
  next.splice(index, 1);
  form.artistTokens = next;
  handleArtistChange(next);
}

function searchArtists(query: string) {
  return loadArtistOptions(query);
}

// ------------------------------------------------------------------ 专辑联想

/**
 * 当前主歌手 id（用于专辑联想排序）。
 *
 * <p>只有「已有歌手」才有 id；若第一位是待新建的名字，则取第一个有 id 的歌手。
 * 返回 null 表示无法判定（没有任何已有歌手），此时专辑联想不做歌手优先。
 */
function currentArtistId(): number | null {
  const ids = selectedArtistIds.value;
  return ids.length ? ids[0] : null;
}

/** 清空专辑归属：只清 id 与选中态，不动输入框文字。 */
function clearAlbumSelection() {
  albumId.value = null;
  selectedAlbum.value = null;
}

/**
 * 输入变化：清空归属 + 防抖查询。
 *
 * <p>输入框走 {@code v-model="form.album"}，所以文字已经写回 model，这里
 * <b>不再重复赋值</b> —— 二次赋值会让光标跳到末尾，中文输入法下尤其明显。
 *
 * <p>清空 albumId 是必须的：用户可能先选中《太阳之子》，随后把文字改成
 * 《太阳之子2》。若不清空，提交时仍会带着旧 albumId，把歌塞进错误的专辑。
 */
function handleAlbumInput() {
  clearAlbumSelection();
  scheduleAlbumSuggest(form.album);
}

/** 防抖 300ms：避免每敲一个字就打一次数据库。 */
function scheduleAlbumSuggest(keyword: string) {
  if (albumSuggestTimer) {
    clearTimeout(albumSuggestTimer);
    albumSuggestTimer = null;
  }
  const trimmed = keyword.trim();
  if (!trimmed) {
    albumSuggestions.value = [];
    return;
  }
  albumSuggestTimer = setTimeout(() => {
    fetchAlbumSuggestions(trimmed);
  }, 300);
}

async function fetchAlbumSuggestions(keyword: string) {
  albumSuggestLoading.value = true;
  try {
    const res = await suggestAlbums(keyword, currentArtistId(), 10);
    if (res.code === 0) {
      albumSuggestions.value = res.data ?? [];
    } else {
      albumSuggestions.value = [];
    }
  } catch {
    // 联想失败不该打断填写流程：静默降级为「没有候选」，用户仍可手输
    albumSuggestions.value = [];
  } finally {
    albumSuggestLoading.value = false;
  }
}

/** el-autocomplete 的取数回调：把已拿到的候选转成它要的 value 字段。 */
function queryAlbumSuggestions(
  queryString: string,
  callback: (items: Array<{ value: string; item: AlbumSuggestItem }>) => void
) {
  const trimmed = queryString.trim();
  if (!trimmed) {
    albumSuggestions.value = [];
    callback([]);
    return;
  }
  fetchAlbumSuggestions(trimmed).then(() => {
    callback(
      albumSuggestions.value.map(item => ({
        value: item.albumName,
        item
      }))
    );
  });
}

/**
 * 选中一个候选：绑定 albumId，并把专辑名回填到输入框。
 *
 * <p>回填名字很重要 —— 否则输入框里可能还是用户手打的不完整文字，
 * 而实际归入的是候选里那张专辑，两者不一致会造成困惑。
 */
function handleAlbumSelect(payload: { item?: AlbumSuggestItem }) {
  const item = payload?.item;
  if (!item) return;
  form.album = item.albumName;
  albumId.value = item.albumId;
  selectedAlbum.value = item;
  albumSuggestions.value = [];
}

/** 专辑状态的展示文案。 */
const albumHint = computed(() => {
  if (!form.album.trim()) {
    return { type: "info" as const, text: "可选，留空则不归入任何专辑" };
  }
  if (selectedAlbum.value) {
    const artist = selectedAlbum.value.artistName || "未知歌手";
    const count = selectedAlbum.value.songCount ?? 0;
    return {
      type: "success" as const,
      text: `将归入已有专辑《${selectedAlbum.value.albumName}》（${artist}，${count} 首）`
    };
  }
  const artistId = currentArtistId();
  if (!artistId) {
    // 没有歌手时无法判定归属（同名专辑可能属于不同歌手），只提示不结论
    return {
      type: "info" as const,
      text: "请先选择歌手，才能判断该专辑是否已存在"
    };
  }
  return {
    type: "warning" as const,
    text: `专辑「${form.album.trim()}」不存在，保存时将自动创建`
  };
});

async function handleCommit() {
  if (!uploadId.value) {
    message("请先上传并解析音频文件", { type: "warning" });
    return;
  }
  if (!form.songName.trim()) {
    message("请填写歌曲名", { type: "warning" });
    return;
  }
  if (!form.artistTokens.length) {
    message("请选择或输入歌手", { type: "warning" });
    return;
  }

  if (!form.releaseDate) {
    message("请填写发行日期", { type: "warning" });
    return;
  }

  committing.value = true;
  try {
    const res = await commitSongUpload({
      uploadId: uploadId.value,
      songName: form.songName.trim(),
      // 已有的歌手传 id，待新建的传名字；两者可能混排，用 artistOrder 给出权威顺序
      // （新歌手完全可能是主艺人，即排在第一位）。
      artistIds: selectedArtistIds.value.length ? selectedArtistIds.value : null,
      pendingArtistNames: pendingArtistNames.value.length
        ? pendingArtistNames.value
        : null,
      artistOrder: form.artistTokens.length ? form.artistTokens : null,
      album: form.album.trim(),
      // 选中已有专辑时带上 albumId：后端 resolveAlbumId 会直接用它，
      // 不必再按 (artistId, 专辑名) 反查，也就不怕同名专辑属于不同歌手。
      albumId: albumId.value,
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
  coverCandidates.value = [];
  coverDialogVisible.value = false;
  coverKeyword.value = "";
  coverSearched.value = false;
  coverManualMode.value = false;
  selectedAlbumMid.value = "";
  coverSize.value = 500;
  albumSuggestions.value = [];
  albumSuggestLoading.value = false;
  clearAlbumSelection();
  if (albumSuggestTimer) {
    clearTimeout(albumSuggestTimer);
    albumSuggestTimer = null;
  }
  clearLocalCover();
  form.songName = "";
  form.artistTokens = [];
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

/** 秒数转 m:ss，用于在候选卡片上显示时长（辅助判断是否同一版本）。 */
function formatDuration(seconds?: number | null) {
  if (!seconds || seconds <= 0) return "-";
  const total = Math.round(seconds);
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
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
              <div class="cover-actions">
                <el-button
                  :loading="coverSearching"
                  plain
                  @click="openCoverSearch"
                >
                  <el-icon class="mr-1"><Search /></el-icon>
                  搜索封面
                </el-button>
                <el-upload
                  action="#"
                  accept=".jpg,.jpeg,.png"
                  :auto-upload="false"
                  :show-file-list="false"
                  :on-change="handleCoverChange"
                >
                  <el-button :loading="assetsUploading" plain>上传封面</el-button>
                </el-upload>
              </div>
              <div class="cover-source">{{ coverSourceText }}</div>
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
                      <!--
                        一个选择器同时支持「选已有歌手」和「输入新名字创建」。

                        为什么不再用两个输入框：那两个框是互斥的两条提交路径
                        （artistIds / artistName），用户不知道该用哪个；而且第二个框
                        在选中系统歌手那一刻会被销毁，已输入的文字跟着消失。
                        现在统一成一个有序 token 列表，输入的文字立刻变成标签，
                        不会消失，顺序即展示顺序。

                        allow-create + default-first-option：让「输入的名字」可以直接回车选中。
                      -->
                      <el-select
                        ref="artistSelectRef"
                        v-model="form.artistTokens"
                        multiple
                        filterable
                        remote
                        allow-create
                        default-first-option
                        :reserve-keyword="false"
                        :remote-method="searchArtists"
                        :loading="artistSearching"
                        placeholder="搜索已有歌手，或直接输入新歌手名后回车"
                        class="w-full"
                        @change="handleArtistChange"
                      >
                        <el-option
                          v-for="item in artistOptions"
                          :key="ARTIST_TOKEN_ID + item.artistId"
                          :label="item.artistName"
                          :value="ARTIST_TOKEN_ID + item.artistId"
                        >
                          <div class="artist-option">
                            <span>{{ item.artistName }}</span>
                            <small>ID: {{ item.artistId }}</small>
                          </div>
                        </el-option>
                      </el-select>

                      <!-- 标签区：逐个展示，区分「已有」与「待创建」 -->
                      <div v-if="form.artistTokens.length" class="artist-tags">
                        <el-tag
                          v-for="(token, index) in form.artistTokens"
                          :key="token"
                          :type="
                            token.startsWith(ARTIST_TOKEN_NAME)
                              ? 'warning'
                              : 'success'
                          "
                          effect="light"
                          closable
                          @close="removeArtistToken(index)"
                        >
                          {{ artistNameOf(token) }}
                          <span v-if="index === 0" class="artist-primary">
                            主艺人
                          </span>
                          <span
                            v-else-if="token.startsWith(ARTIST_TOKEN_NAME)"
                            class="artist-new"
                          >
                            待创建
                          </span>
                        </el-tag>
                      </div>
                      <el-tag v-else type="info" effect="light">
                        请选择或输入歌手
                      </el-tag>

                      <p v-if="hasPendingArtists" class="artist-hint">
                        标为「待创建」的歌手在保存时会自动创建；
                        若不希望新建，请先在「歌手管理」中创建后再回来选择。
                      </p>
                      <p
                        v-else-if="form.artistTokens.length && artistMatched"
                        class="artist-hint is-ok"
                      >
                        全部歌手均已存在于系统中，保存时直接关联。
                      </p>
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
                    <div class="album-field">
                      <el-autocomplete
                        v-model="form.album"
                        :fetch-suggestions="queryAlbumSuggestions"
                        :trigger-on-focus="false"
                        :debounce="0"
                        clearable
                        placeholder="可选，输入专辑名会联想已有专辑"
                        class="w-full"
                        popper-class="album-suggest-popper"
                        @input="handleAlbumInput"
                        @select="handleAlbumSelect"
                      >
                        <template #default="{ item }">
                          <div class="album-option">
                            <el-image
                              v-if="item.item.coverUrl"
                              :src="item.item.coverUrl"
                              fit="cover"
                              class="album-option__cover"
                            />
                            <div v-else class="album-option__cover placeholder">
                              <el-icon><Headset /></el-icon>
                            </div>
                            <div class="album-option__meta">
                              <div class="album-option__title">
                                <span>{{ item.item.albumName }}</span>
                                <el-tag
                                  v-if="item.item.artistStatus === 'DISABLED'"
                                  size="small"
                                  type="info"
                                  effect="plain"
                                >
                                  已停用
                                </el-tag>
                              </div>
                              <span class="album-option__sub">
                                {{ item.item.artistName || "未知歌手" }}
                                <template v-if="item.item.songCount">
                                  · {{ item.item.songCount }} 首
                                </template>
                                <template v-if="item.item.releaseDate">
                                  · {{ item.item.releaseDate }}
                                </template>
                              </span>
                            </div>
                          </div>
                        </template>
                      </el-autocomplete>

                      <div class="album-hint" :class="`is-${albumHint.type}`">
                        <el-icon v-if="albumSuggestLoading" class="is-loading">
                          <Loading />
                        </el-icon>
                        {{ albumHint.text }}
                      </div>
                    </div>
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

        <!-- 封面搜索：自动按表单元数据搜，也可手动输入关键词重搜 -->
        <el-dialog
          v-model="coverDialogVisible"
          title="搜索封面"
          width="720px"
          align-center
          destroy-on-close
        >
          <div class="cover-search-panel">
            <div class="cover-search-mode">
              <el-radio-group v-model="coverManualMode" size="small">
                <el-radio-button :value="false">按歌曲信息搜索</el-radio-button>
                <el-radio-button :value="true">手动搜索</el-radio-button>
              </el-radio-group>
              <span class="cover-search-hint">
                {{
                  coverManualMode
                    ? "直接输入歌名或专辑名，不再用表单信息过滤"
                    : "使用表单里的歌名、歌手、专辑与音频时长"
                }}
              </span>
            </div>

            <!-- 手动模式：元数据解析错时的逃生通道 -->
            <div v-if="coverManualMode" class="cover-search-manual">
              <el-input
                v-model="coverKeyword"
                clearable
                placeholder="输入歌名或专辑名，例如「青花瓷」或「我很忙」"
                @keyup.enter="searchCoversManual"
              />
              <el-button
                type="primary"
                :loading="coverSearching"
                @click="searchCoversManual"
              >
                搜索
              </el-button>
            </div>
            <div v-else class="cover-search-manual">
              <el-button
                :loading="coverSearching"
                plain
                @click="searchCoversAuto"
              >
                重新按歌曲信息搜索
              </el-button>
              <span class="cover-search-hint">
                解析错了？切到「手动搜索」自己输入关键词
              </span>
            </div>

            <div class="cover-size-bar">
              <span>尺寸</span>
              <el-radio-group v-model="coverSize" size="small">
                <el-radio-button :value="300">300</el-radio-button>
                <el-radio-button :value="500">500</el-radio-button>
                <el-radio-button :value="800">800</el-radio-button>
              </el-radio-group>
            </div>

            <div v-if="coverSearching" class="cover-search-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
              正在搜索封面...
            </div>
            <div v-else-if="coverCandidates.length" class="cover-grid">
              <button
                v-for="candidate in coverCandidates"
                :key="candidate.albumMid"
                type="button"
                class="cover-card"
                :class="{ selected: selectedAlbumMid === candidate.albumMid }"
                :disabled="coverApplying"
                @click="applyCover(candidate)"
              >
                <img
                  :src="coverUrlOf(candidate, coverSize)"
                  :alt="candidate.albumName || candidate.trackName || '封面'"
                  loading="lazy"
                />
                <div class="cover-card__meta">
                  <strong>{{ candidate.albumName || "未命名专辑" }}</strong>
                  <span>{{ candidate.artistName || "-" }}</span>
                  <div class="cover-card__tags">
                    <el-tag
                      v-if="candidate.versionLabel"
                      size="small"
                      :type="
                        candidate.versionLabel === '录音室' ? 'success' : 'warning'
                      "
                      effect="light"
                    >
                      {{ candidate.versionLabel }}
                    </el-tag>
                    <el-tag
                      v-if="candidate.durationSeconds"
                      size="small"
                      type="info"
                      effect="plain"
                    >
                      {{ formatDuration(candidate.durationSeconds) }}
                    </el-tag>
                  </div>
                </div>
              </button>
            </div>
            <el-empty
              v-else-if="coverSearched"
              description="没有找到带封面的专辑，换个关键词试试"
              :image-size="72"
            />
          </div>

          <template #footer>
            <el-button @click="coverDialogVisible = false">取消</el-button>
          </template>
        </el-dialog>
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
              <span>{{ selectedArtistNames || "未选择歌手" }}</span>
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
            <li>关闭页面不会创建正式歌曲，临时文件 12 小时无操作后自动清理。</li>
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

.cover-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;

  :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  :deep(.el-upload) {
    display: block;
    width: 100%;
  }
}

/* 封面搜索面板 */
.cover-search-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cover-search-mode,
.cover-size-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.cover-size-bar > span {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.cover-search-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cover-search-manual {
  display: flex;
  gap: 10px;
  align-items: center;
}

.cover-search-loading {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  padding: 32px 0;
  color: var(--el-text-color-secondary);
}

.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 14px;
  max-height: 380px;
  overflow-y: auto;
  padding: 2px;
}

.cover-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-blank);
  border: 2px solid var(--el-border-color-lighter);
  border-radius: 12px;
  transition: 0.18s ease;

  &:hover:not(:disabled) {
    border-color: #3b82f6;
    box-shadow: 0 8px 20px rgb(59 130 246 / 14%);
  }

  &.selected {
    border-color: #22c55e;
    box-shadow: 0 0 0 3px rgb(34 197 94 / 16%);
  }

  &:disabled {
    cursor: progress;
    opacity: 0.65;
  }

  img {
    width: 100%;
    aspect-ratio: 1;
    object-fit: cover;
    background: var(--el-fill-color);
    border-radius: 8px;
  }
}

.cover-card__meta {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;

  strong,
  span {
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--el-text-color-secondary);
  }
}

.cover-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
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

/* 歌手标签区：逐个展示，区分「已有」「待创建」「主艺人」 */
.artist-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.artist-primary,
.artist-new {
  padding: 0 4px;
  margin-left: 4px;
  font-size: 11px;
  border-radius: 3px;
}

.artist-primary {
  color: #1d4ed8;
  background: rgb(59 130 246 / 14%);
}

.artist-new {
  color: #b45309;
  background: rgb(245 158 11 / 16%);
}

.artist-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);

  &.is-ok {
    color: #15803d;
  }
}

/* 专辑联想：输入框 + 状态提示 */
.album-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
}

.album-hint {
  display: flex;
  gap: 5px;
  align-items: center;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);

  &.is-success {
    color: #15803d;
  }

  &.is-warning {
    color: #b45309;
  }
}

/* 下拉项：封面 + 专辑名 + 歌手/歌曲数/发行日期 */
.album-option {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 4px 0;
}

.album-option__cover {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border-radius: 6px;

  &.placeholder {
    display: grid;
    color: var(--el-text-color-placeholder);
    background: var(--el-fill-color);
    place-items: center;
  }
}

.album-option__meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.album-option__title {
  display: flex;
  gap: 6px;
  align-items: center;
  overflow: hidden;
  font-size: 13px;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.album-option__sub {
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
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
