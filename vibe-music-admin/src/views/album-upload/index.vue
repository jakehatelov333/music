<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import type { UploadFile } from "element-plus";
import {
  ArrowLeft,
  ArrowRight,
  CircleCheckFilled,
  EditPen,
  FolderOpened,
  InfoFilled,
  Loading,
  Picture,
  Upload
} from "@element-plus/icons-vue";
import { useRouter } from "vue-router";
import { message } from "@/utils/message";
import { getAllArtists } from "@/api/system";
import {
  ALBUM_UPLOAD_CONFIRMATION_REQUIRED,
  clearAlbumUpload,
  commitAlbumUpload,
  completeAlbumUploadFile,
  createAlbumUploadSession,
  getAlbumUploadSession,
  parseAlbumUpload,
  signAlbumUploadFile,
  type AlbumUploadPendingArtists,
  type AlbumUploadPrepareResult
} from "@/api/albumUpload";

defineOptions({ name: "AlbumUpload" });
const router = useRouter();
const directoryUploadRef = ref<HTMLInputElement>();
const audioFiles = ref<File[]>([]);
const coverFile = ref<File>();
const preparing = ref(false);
const committing = ref(false);
const uploading = ref(false);
const dragging = ref(false);
const selectedFolderName = ref("");
const uploadProgress = ref<Record<string, number>>({});
const uploadErrors = ref<Record<string, string>>({});
const sessionFiles = ref<Array<{ fileName: string; size: number; object: string; uploaded?: boolean }>>([]);
type AlbumDraftForm = { albumName: string; artistName: string; artistId: number | null; releaseDate: string; description: string };
type AlbumDraft = { uploadId: string; fileNames: string[]; updatedAt: number; expiresAt?: number; form?: AlbumDraftForm };
function loadDrafts(): AlbumDraft[] {
  try {
    const parsed = JSON.parse(localStorage.getItem("album-upload-drafts") || "[]");
    return Array.isArray(parsed) ? parsed.filter((item): item is AlbumDraft => !!item && typeof item.uploadId === "string") : [];
  } catch {
    // 本地数据损坏（手改、旧版本残留）时不能阻断整个页面
    return [];
  }
}
const draftTasks = ref<AlbumDraft[]>(loadDrafts());
/** 每分钟走一格，让「剩余时间」和过期隐藏不需要刷新页面就能生效 */
const nowTick = ref(Date.now());
const activeDraftId = ref("");
const prepared = ref<AlbumUploadPrepareResult>();
const coverPreview = ref("");
const form = reactive({ albumName: "", artistName: "", artistId: null as number | null, releaseDate: "", description: "", status: "DRAFT" as const });
/** 主艺人下拉候选：专辑主艺人必须从已存在的歌手里选，不允许在此新建 */
const artistOptions = ref<{ artistId: number; artistName: string }[]>([]);
/** 提交时后端返回的「待创建歌手」清单（code=409），确认后才真正创建 */
const pendingConfirm = ref<AlbumUploadPendingArtists | null>(null);
const pendingConfirmVisible = ref(false);
const canCommit = computed(() => !!prepared.value?.uploadId && !!form.albumName.trim() && prepared.value?.songs?.length > 0 && !committing.value);
/** 解析阶段就提示「哪些歌手库里没有」，让管理员提交前心里有数（提交时会以后端复核为准） */
const pendingArtistPreview = computed(() => {
  const names = new Set<string>();
  (prepared.value?.songs || []).forEach(row => {
    (row.artistParsing?.candidates || []).forEach(candidate => {
      if (!candidate.matched && candidate.artistName) names.add(candidate.artistName);
    });
  });
  return [...names];
});
const stepLabels = ["上传文件", "解析信息", "最终确认"];
const currentStep = computed(() => (prepared.value ? 1 : 0));
const selectionLabel = computed(() => {
  if (!audioFiles.value.length) return "未选择文件夹";
  const folder = selectedFolderName.value ? `${selectedFolderName.value} · ` : "";
  return `${folder}${audioFiles.value.length} 首音频${coverFile.value ? " · 含封面" : ""}`;
});

const AUDIO_EXTENSIONS = new Set(["mp3", "flac", "wav", "m4a", "aac", "ogg", "opus"]);
const COVER_EXTENSIONS = new Set(["jpg", "jpeg", "png", "webp"]);

function extensionOf(name: string) {
  const value = name.toLowerCase().split("?")[0];
  const index = value.lastIndexOf(".");
  return index >= 0 ? value.slice(index + 1) : "";
}

function trackNumberOf(name: string) {
  const match = name.match(/^\s*(\d{1,4})(?:[\s._-]|$)/);
  return match ? Number(match[1]) : Number.MAX_SAFE_INTEGER;
}

function sortAudioFiles(files: File[]) {
  return [...files].sort((left, right) => {
    const numberDiff = trackNumberOf(left.name) - trackNumberOf(right.name);
    return numberDiff || left.name.localeCompare(right.name, undefined, { numeric: true, sensitivity: "base" });
  });
}

function normalizeFiles(files: File[]) {
  const coverCandidate = files.find(raw =>
    COVER_EXTENSIONS.has(extensionOf(raw.name)) &&
    /^cover(?:[._-].*)?$/i.test(raw.name.replace(/\.[^.]+$/, ""))
  );
  if (coverCandidate) {
    coverFile.value = coverCandidate;
    if (coverPreview.value) URL.revokeObjectURL(coverPreview.value);
    coverPreview.value = URL.createObjectURL(coverCandidate);
  }
  audioFiles.value = sortAudioFiles(
    files.filter(raw => AUDIO_EXTENSIONS.has(extensionOf(raw.name)))
  );
  return coverCandidate;
}

/** 打开系统文件夹选择器（点击拖拽区或按钮时触发） */
function triggerDirectoryPick() {
  directoryUploadRef.value?.click();
}

function onDragOver() {
  dragging.value = true;
}

function onDragLeave() {
  dragging.value = false;
}

/** 递归读取拖拽进来的文件夹条目 */
async function readEntry(entry: any): Promise<File[]> {
  if (!entry) return [];
  if (entry.isFile) {
    return await new Promise<File[]>(resolve => entry.file((file: File) => resolve([file]), () => resolve([])));
  }
  if (entry.isDirectory) {
    const reader = entry.createReader();
    const readBatch = () => new Promise<any[]>(resolve => reader.readEntries((batch: any[]) => resolve(batch), () => resolve([])));
    const children: any[] = [];
    let batch = await readBatch();
    while (batch.length) {
      children.push(...batch);
      batch = await readBatch();
    }
    const nested = await Promise.all(children.map(readEntry));
    return nested.flat();
  }
  return [];
}

async function onDrop(event: DragEvent) {
  dragging.value = false;
  const transfer = event.dataTransfer;
  if (!transfer) return;
  // webkitGetAsEntry 必须在事件回调同步阶段取出，否则条目会失效
  const entries = Array.from(transfer.items || [])
    .map(item => (item as any).webkitGetAsEntry?.())
    .filter(Boolean);
  let files: File[] = [];
  if (entries.length) {
    const nested = await Promise.all(entries.map(readEntry));
    files = nested.flat();
  }
  if (!files.length) files = Array.from(transfer.files || []);
  if (!files.length) return;
  const folderName = entries[0]?.name || files[0]?.webkitRelativePath?.split("/")[0] || "";
  selectedFolderName.value = folderName;
  const coverCandidate = normalizeFiles(files);
  message(`已读取 ${audioFiles.value.length} 首音频${coverCandidate ? "，已识别专辑封面" : ""}`, { type: "info" });
}

function onNativeDirectoryChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  selectedFolderName.value = files[0]?.webkitRelativePath?.split("/")[0] || "";
  const coverCandidate = normalizeFiles(files);
  message(`已读取 ${audioFiles.value.length} 首音频${coverCandidate ? "，已识别专辑封面" : ""}`, { type: "info" });
  input.value = "";
}

function onCoverChange(file: UploadFile) {
  if (!file.raw) return;
  coverFile.value = file.raw;
  if (coverPreview.value) URL.revokeObjectURL(coverPreview.value);
  coverPreview.value = URL.createObjectURL(file.raw);
}
function persistDrafts() {
  const storageKey = "album-upload-drafts";
  let removed = 0;
  while (removed < 100) {
    try {
      localStorage.setItem(storageKey, JSON.stringify(draftTasks.value));
      if (removed > 0) message(`本地存储空间不足，已清理 ${removed} 条最旧的草稿`, { type: "warning" });
      return;
    } catch {
      // 配额满时从最旧的一条开始丢：宁可少一条历史草稿，也不能丢掉当前正在上传的这条
      if (draftTasks.value.length <= 1) break;
      const oldest = [...draftTasks.value].sort((left, right) => left.updatedAt - right.updatedAt)[0];
      draftTasks.value = draftTasks.value.filter(item => item.uploadId !== oldest.uploadId);
      removed++;
    }
  }
}
function snapshotForm(): AlbumDraftForm {
  return { albumName: form.albumName, artistName: form.artistName, artistId: form.artistId, releaseDate: form.releaseDate, description: form.description };
}
function saveDraft(uploadId: string, files: File[], expiresAt?: number) {
  const existing = draftTasks.value.find(item => item.uploadId === uploadId);
  const current = draftTasks.value.filter(item => item.uploadId !== uploadId);
  draftTasks.value = [
    {
      uploadId,
      fileNames: files.map(file => file.name),
      updatedAt: Date.now(),
      // 过期时间只认服务端下发的绝对时间，表单编辑不会续期
      expiresAt: expiresAt ?? existing?.expiresAt,
      form: existing?.form ?? snapshotForm()
    },
    ...current
  ];
  persistDrafts();
}
/** 把服务端返回的绝对过期时间写回草稿（签约/完成文件会续期，必须以后端为准） */
function updateDraftExpiry(uploadId: string, expiresAt?: number) {
  if (!expiresAt) return;
  const index = draftTasks.value.findIndex(item => item.uploadId === uploadId);
  if (index < 0 || draftTasks.value[index].expiresAt === expiresAt) return;
  draftTasks.value[index] = { ...draftTasks.value[index], expiresAt };
  persistDrafts();
}
/** 把当前表单（含发行日期等）写回草稿箱，保证「复用草稿」时能恢复用户填写内容 */
function updateDraftForm(uploadId: string) {
  const index = draftTasks.value.findIndex(item => item.uploadId === uploadId);
  if (index < 0) return;
  draftTasks.value[index] = { ...draftTasks.value[index], form: snapshotForm(), updatedAt: Date.now() };
  persistDrafts();
}
function removeDraft(uploadId: string) {
  draftTasks.value = draftTasks.value.filter(item => item.uploadId !== uploadId);
  persistDrafts();
}
async function clearDraft(uploadId: string) {
  await clearAlbumUpload(uploadId).catch(() => undefined);
  removeDraft(uploadId);
  if (localStorage.getItem("album-upload-id") === uploadId) localStorage.removeItem("album-upload-id");
}
/**
 * 进页面时主动核对草稿。
 *
 * <p>localStorage 没有 TTL，草稿条目会一直留在列表里；只有点「继续」才校验的话，
 * 界面会长期显示已经失效的草稿。这里一次性问服务端要会话，只移除「服务端明确说没有」的条目；
 * 请求本身失败（断网、后端没起）时保留草稿，避免误删。</p>
 */
async function validateDrafts() {
  if (!draftTasks.value.length) return;
  const checks = await Promise.all(
    draftTasks.value.map(async draft => {
      const result = await getAlbumUploadSession(draft.uploadId).catch(() => null);
      return { uploadId: draft.uploadId, alive: result?.data, failed: !result };
    })
  );
  const expired: string[] = [];
  for (const check of checks) {
    // 请求失败（断网、后端没起）只保留，不能当成过期
    if (check.failed) continue;
    if (!check.alive) {
      expired.push(check.uploadId);
      continue;
    }
    // 服务端才是过期时间的权威：本地展示用的 expiresAt 每次都按真实 TTL 校正
    updateDraftExpiry(check.uploadId, check.alive.expiresAt);
  }
  for (const uploadId of expired) {
    await clearDraft(uploadId);
  }
  if (expired.length) message(`已清理 ${expired.length} 个过期草稿`, { type: "info" });
}
/** 草稿箱展示全部条目：只隐藏本地判断已过期的，等上面一次服务端校正把它们救回来或清掉 */
const visibleDrafts = computed(() => {
  const now = nowTick.value;
  return [...draftTasks.value]
    .filter(draft => !draft.expiresAt || draft.expiresAt > now)
    .sort((left, right) => {
      if (left.uploadId === activeDraftId.value) return -1;
      if (right.uploadId === activeDraftId.value) return 1;
      return right.updatedAt - left.updatedAt;
    });
});
function draftRemaining(draft: AlbumDraft) {
  if (!draft.expiresAt) return "";
  const minutes = Math.max(0, Math.round((draft.expiresAt - nowTick.value) / 60_000));
  if (minutes <= 0) return "已过期";
  if (minutes < 60) return `${minutes} 分钟后过期`;
  return `${Math.round(minutes / 60)} 小时后过期`;
}
function isDraftExpiringSoon(draft: AlbumDraft) {
  return !!draft.expiresAt && draft.expiresAt - nowTick.value < 6 * 3600_000;
}
async function openDraft(uploadId: string) {
  const result = await getAlbumUploadSession(uploadId).catch(() => null);
  if (!result) {
    // 网络错误不能当成「草稿过期」，否则一次断网就会把还能继续的草稿删掉
    return message("无法连接服务器，请稍后重试", { type: "error" });
  }
  if (!result.data) {
    await clearDraft(uploadId);
    return message("草稿已过期，已从草稿箱移除", { type: "warning" });
  }
  updateDraftExpiry(uploadId, result.data.expiresAt);
  activeDraftId.value = uploadId;
  localStorage.setItem("album-upload-id", uploadId);
  sessionFiles.value = (result.data.files || []) as any;
  prepared.value = undefined;
  const uploaded = sessionFiles.value.filter(item => item.uploaded !== false);
  if (uploaded.length === sessionFiles.value.length && uploaded.length > 0) {
    preparing.value = true;
    try {
      const parsed = await parseAlbumUpload(uploadId);
      if (parsed.code !== 0 || !parsed.data) throw new Error(parsed.message || "草稿解析失败");
      prepared.value = parsed.data;
      form.albumName = parsed.data.albumName || "";
      form.artistName = parsed.data.artistName || "";
      form.artistId = parsed.data.artistId ?? null;
      form.releaseDate = parsed.data.releaseDate || "";
      // 草稿箱中保存的用户填写内容优先于解析结果（如发行日期、专辑名等）
      const savedForm = draftTasks.value.find(item => item.uploadId === uploadId)?.form;
      if (savedForm) {
        form.albumName = savedForm.albumName || form.albumName;
        form.artistName = savedForm.artistName || form.artistName;
        form.artistId = savedForm.artistId ?? form.artistId;
        form.releaseDate = savedForm.releaseDate || form.releaseDate;
        form.description = savedForm.description ?? form.description;
      }
      await syncPrimaryArtistId();
      message("草稿已恢复，可以继续校正并提交", { type: "success" });
    } catch (error: any) {
      message(error?.message || "草稿解析失败", { type: "error" });
    } finally {
      preparing.value = false;
    }
  } else {
    message("草稿已打开，请选择原专辑文件夹补传未完成的文件", { type: "info" });
  }
}
/** 拉取全部歌手名（不分页），用于主艺人下拉与「名字 → id」回填。 */
async function loadArtistOptions() {
  if (artistOptions.value.length) return artistOptions.value;
  const res = await getAllArtists().catch(() => null);
  artistOptions.value = Array.isArray(res?.data)
    ? (res.data as Array<{ artistId?: number; artistName?: string }>)
        .filter(item => item?.artistId && item?.artistName)
        .map(item => ({ artistId: item.artistId as number, artistName: item.artistName as string }))
    : [];
  return artistOptions.value;
}

/**
 * 把解析出的主艺人名字回填成下拉里的 id。
 * 专辑主艺人必须已存在（不在上传流程里新建），所以名字匹配不上时保持为空、由管理员手选。
 */
async function syncPrimaryArtistId() {
  const options = await loadArtistOptions();
  if (form.artistId && options.some(item => item.artistId === form.artistId)) return;
  const name = form.artistName.trim();
  form.artistId = name
    ? options.find(item => item.artistName.trim() === name)?.artistId ?? null
    : null;
}

function onPrimaryArtistChange(id: number | null) {
  form.artistName = artistOptions.value.find(item => item.artistId === id)?.artistName || "";
}

/** 该曲目解析出的候选歌手（未匹配的名字会以红色标签提示） */
function parsingCandidates(row: { artistParsing?: { candidates?: Array<{ artistId?: number | null; artistName: string; matched: boolean }> } | null }) {
  return row?.artistParsing?.candidates || [];
}

/**
 * 手动改过某行的歌手名后，丢弃解析阶段给出的 id / 结论。
 * 否则后端会优先采用旧的 artistIds，把管理员刚改的内容悄悄盖掉。
 */
function onSongArtistInput(row: { artistIds?: number[] | null; artistParsing?: unknown }) {
  row.artistIds = null;
  row.artistParsing = null;
}

async function prepare() {
  if (!audioFiles.value.length) return message("请先选择至少一首音频", { type: "warning" });
  preparing.value = true;
  try {
    let uploadId = activeDraftId.value || localStorage.getItem("album-upload-id") || "";
    let draftExpiresAt: number | undefined;
    const selectedKeys = [...audioFiles.value, ...(coverFile.value ? [coverFile.value] : [])].map(file => `${file.name}:${file.size}`).sort().join("|");
    if (uploadId) {
      const existing = await getAlbumUploadSession(uploadId).catch(() => null);
      const existingFiles = existing?.data?.files || [];
      draftExpiresAt = existing?.data?.expiresAt;
      const existingKeys = existingFiles.map((item: any) => `${item.fileName}:${item.size}`).sort().join("|");
      if (!existing?.data) {
        localStorage.removeItem("album-upload-id");
        activeDraftId.value = "";
        uploadId = "";
      } else if (existingKeys && existingKeys !== selectedKeys) {
        const selectedMap = new Map([...audioFiles.value, ...(coverFile.value ? [coverFile.value] : [])].map(file => [`${file.name}:${file.size}`, file]));
        const missingFromSelection = existingFiles.some((item: any) => !selectedMap.has(`${item.fileName}:${item.size}`));
        if (missingFromSelection) {
          return message("请重新选择与草稿相同的专辑文件夹，或先放弃该草稿", { type: "warning" });
        }
      } else {
        sessionFiles.value = existingFiles as any;
      }
    }
    if (!uploadId) {
      const session = await createAlbumUploadSession([
        ...audioFiles.value.map(file => ({ fileName: file.name, size: file.size, contentType: file.type, cover: false })),
        ...(coverFile.value ? [{ fileName: coverFile.value.name, size: coverFile.value.size, contentType: coverFile.value.type, cover: true }] : [])
      ]);
      if (session.code !== 0 || !session.data?.uploadId) throw new Error(session.message || "创建上传任务失败");
      uploadId = session.data.uploadId;
      draftExpiresAt = session.data.expiresAt;
      localStorage.setItem("album-upload-id", uploadId);
      sessionFiles.value = (session.data as any).files || [];
    }
    saveDraft(uploadId, [...audioFiles.value, ...(coverFile.value ? [coverFile.value] : [])], draftExpiresAt);
    uploading.value = true;
    const files = [...audioFiles.value, ...(coverFile.value ? [coverFile.value] : [])];
    let cursor = 0;
    const worker = async () => {
      while (cursor < files.length) {
        const file = files[cursor++];
        const key = `${file.name}:${file.size}`;
        const staged = sessionFiles.value.find(item => item.fileName === file.name && item.size === file.size);
        if (staged?.uploaded || uploadProgress.value[key] === 100) { uploadProgress.value[key] = 100; continue; }
        try {
          const signed = await signAlbumUploadFile(uploadId!, file, file === coverFile.value);
          if (signed.code !== 0 || !signed.data?.url) throw new Error(signed.message || "获取上传地址失败");
          const response = await fetch(signed.data.url, { method: "PUT", headers: file.type ? { "Content-Type": file.type } : undefined, body: file });
          if (!response.ok) throw new Error(`文件上传失败（${response.status}）`);
          const done = await completeAlbumUploadFile(uploadId!, file, signed.data.objectName);
          if (done.code !== 0) throw new Error(done.message || "确认上传失败");
          uploadProgress.value[key] = 100;
          uploadErrors.value[key] = "";
        } catch (error: any) {
          uploadErrors.value[key] = error?.message || "上传失败";
          throw error;
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(3, files.length) }, worker));
    uploading.value = false;
    const res = await parseAlbumUpload(uploadId!);
    if (res.code !== 0 || !res.data) throw new Error(res.message || "专辑解析失败");
    prepared.value = res.data;
    form.albumName = res.data.albumName || "";
    form.artistName = res.data.artistName || "";
    form.artistId = res.data.artistId ?? null;
    await syncPrimaryArtistId();
    if (!form.artistId) message(`未找到主艺人“${form.artistName || ""}”，请在「主艺人」下拉中选择已存在的歌手`, { type: "warning" });
    form.releaseDate = res.data.releaseDate || "";
    message("专辑解析完成，请核对并修改信息", { type: "success" });
  } catch (error: any) { message(error?.message || "专辑解析失败", { type: "error" }); }
  finally { uploading.value = false; preparing.value = false; }
}
/**
 * 提交专辑。
 *
 * <p>第一次提交不带 `allowCreate`：只要曲目里出现库里不存在的歌手，后端只返回待创建清单
 * （`code = 409`，不写任何数据）。管理员在弹框里确认后，才带 `allowCreate: true` 重提，
 * 后端创建歌手并继续入库。
 */
async function commit(allowCreate = false) {
  if (!prepared.value || !canCommit.value) return;
  if (!form.artistId) return message("请先选择专辑主艺人（必须是已存在的歌手）", { type: "warning" });
  if (form.releaseDate && form.releaseDate > todayString()) {
    return message("发行日期不能晚于今天", { type: "warning" });
  }
  committing.value = true;
  try {
    const res = await commitAlbumUpload({
      uploadId: prepared.value.uploadId,
      album: { ...form },
      songs: prepared.value.songs,
      allowCreate
    });
    if (res.code === ALBUM_UPLOAD_CONFIRMATION_REQUIRED) {
      pendingConfirm.value = (res.data as AlbumUploadPendingArtists) || null;
      pendingConfirmVisible.value = !!pendingConfirm.value?.missingArtists?.length;
      if (!pendingConfirmVisible.value) message(res.message || "提交需要人工确认", { type: "warning" });
      return;
    }
    if (res.code !== 0) throw new Error(res.message || "专辑保存失败");
    message(`专辑已保存，共 ${res.data?.songCount ?? prepared.value.songs.length} 首歌曲`, { type: "success" });
    removeDraft(prepared.value.uploadId);
    localStorage.removeItem("album-upload-id");
    router.push("/album/index");
  } catch (error: any) { message(error?.message || "专辑保存失败", { type: "error" }); }
  finally { committing.value = false; }
}

/** 弹框里确认「创建缺失歌手并继续上传」 */
function confirmCreateArtists() {
  pendingConfirmVisible.value = false;
  commit(true);
}
async function clear() {
  // 先取 id 再清空状态：之前 removeDraft 放在 prepared.value = undefined 之后，
  // 条件永远为 false，导致「清空任务」后草稿箱里还留着一条指向已删会话的死条目。
  const uploadId = prepared.value?.uploadId || activeDraftId.value || localStorage.getItem("album-upload-id") || "";
  if (uploadId) await clearAlbumUpload(uploadId).catch(() => undefined);
  prepared.value = undefined; audioFiles.value = []; coverFile.value = undefined; form.albumName = ""; form.artistName = ""; form.artistId = null; form.releaseDate = ""; form.description = "";
  localStorage.removeItem("album-upload-id"); uploadProgress.value = {}; uploadErrors.value = {}; sessionFiles.value = [];
  pendingConfirm.value = null; pendingConfirmVisible.value = false;
  if (uploadId) removeDraft(uploadId);
  activeDraftId.value = "";
  selectedFolderName.value = "";
}
function todayString() {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
/** 禁用未来日期：发行日期不允许超过今天（不需要预发布） */
function disableFutureDate(date: Date) {
  const endOfToday = new Date();
  endOfToday.setHours(23, 59, 59, 999);
  return date.getTime() > endOfToday.getTime();
}
watch(form, () => {
  const id = prepared.value?.uploadId || activeDraftId.value || localStorage.getItem("album-upload-id") || "";
  if (id) updateDraftForm(id);
}, { deep: true });
let clockTimer: number | undefined;
onMounted(() => {
  validateDrafts();
  clockTimer = window.setInterval(() => {
    nowTick.value = Date.now();
  }, 60_000);
});
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  if (coverPreview.value) URL.revokeObjectURL(coverPreview.value);
});
</script>

<template>
  <div class="album-upload-page">
    <!-- 顶部标题区 -->
    <section class="hero-card">
      <div class="hero-icon">
        <IconifyIconOnline icon="ri:album-line" width="32" />
      </div>
      <div class="hero-copy">
        <h1>上传专辑</h1>
        <p>一次上传整张专辑，自动解析曲目与元数据，校对后一次性入库。</p>
      </div>
      <el-button class="hero-back" :icon="ArrowLeft" @click="router.push('/album/index')">
        返回专辑管理
      </el-button>
    </section>

    <!-- 步骤条 -->
    <section class="steps-bar">
      <div class="stepper">
        <div
          v-for="(label, index) in stepLabels"
          :key="label"
          class="step"
          :class="{ 'is-active': currentStep === index, 'is-done': currentStep > index }"
        >
          <span class="step-dot">
            <el-icon v-if="currentStep > index"><CircleCheckFilled /></el-icon>
            <template v-else>{{ index + 1 }}</template>
          </span>
          <span class="step-label">{{ label }}</span>
        </div>
      </div>
    </section>

    <!-- 草稿箱：常驻显示，没有草稿时给出空态提示 -->
    <section class="panel draft-panel">
      <div class="draft-head">
        <el-icon><InfoFilled /></el-icon>
        <span>上传草稿箱（{{ visibleDrafts.length }}）</span>
      </div>
      <template v-if="visibleDrafts.length">
        <div v-for="draft in visibleDrafts" :key="draft.uploadId" class="draft-row">
          <span class="draft-files">
            {{ draft.fileNames.length }} 个文件 · {{ draft.fileNames.slice(0, 3).join("、") }}{{ draft.fileNames.length > 3 ? " …" : "" }}
          </span>
          <span class="draft-actions">
            <span v-if="draftRemaining(draft)" class="draft-expiry" :class="{ 'is-soon': isDraftExpiringSoon(draft) }">
              {{ draftRemaining(draft) }}
            </span>
            <el-button size="small" text type="primary" @click="openDraft(draft.uploadId)">继续</el-button>
            <el-button size="small" text type="danger" @click="clearDraft(draft.uploadId)">放弃</el-button>
          </span>
        </div>
        <p class="draft-note">草稿有有效期，到期后会自动从列表移除，暂存文件也会被服务端回收。</p>
      </template>
      <p v-else class="draft-empty">暂无草稿。上传整张专辑后会自动暂存到这里，未提交的草稿到期会自动清理。</p>
    </section>
    <section v-if="activeDraftId && sessionFiles.length" class="panel draft-panel is-success">
      <div class="draft-head">
        <el-icon><CircleCheckFilled /></el-icon>
        <span>草稿已恢复：{{ sessionFiles.filter(file => file.uploaded !== false).length }}/{{ sessionFiles.length }} 个文件已在服务器</span>
      </div>
      <p class="draft-note">可以直接解析已上传文件并继续设置专辑。需要补传时，再选择原文件夹。</p>
    </section>

    <!-- 步骤 1：上传文件 -->
    <section class="panel">
      <div class="panel-heading">
        <span class="panel-badge"><IconifyIconOnline icon="ri:folder-upload-line" width="18" /></span>
        <div>
          <h2>上传专辑文件</h2>
          <p>选择或拖入整张专辑文件夹，系统会自动读取音频标签并识别封面。</p>
        </div>
      </div>

      <div
        class="dropzone"
        :class="{ 'is-dragging': dragging }"
        role="button"
        tabindex="0"
        aria-label="选择或拖入专辑文件夹"
        @click="triggerDirectoryPick"
        @keydown.enter.prevent="triggerDirectoryPick"
        @keydown.space.prevent="triggerDirectoryPick"
        @dragover.prevent="onDragOver"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="onDrop"
      >
        <input
          ref="directoryUploadRef"
          class="hidden-input"
          type="file"
          webkitdirectory
          directory
          multiple
          accept=".mp3,.flac,.wav,.m4a,.aac,.ogg,.opus,.jpg,.jpeg,.png,.webp"
          @change="onNativeDirectoryChange"
        />
        <div class="drop-icon">
          <IconifyIconOnline icon="ri:folder-music-line" width="40" />
        </div>
        <strong>拖拽专辑文件夹到这里</strong>
        <span class="drop-sub">也可以点击选择文件夹，支持 MP3、FLAC 等音频文件</span>
        <el-button type="primary" size="large" :icon="FolderOpened">选择专辑文件夹</el-button>
        <div class="drop-hint">
          <el-icon><InfoFilled /></el-icon>
          系统将自动读取音频标签并识别 cover.jpg / cover.png
        </div>
      </div>

      <div class="cover-section">
        <div class="cover-thumb">
          <el-image
            v-if="coverPreview"
            :src="coverPreview"
            fit="cover"
            class="cover-img"
            :preview-src-list="[coverPreview]"
            preview-teleported
          />
          <el-icon v-else class="cover-placeholder"><Picture /></el-icon>
        </div>
        <div class="cover-copy">
          <strong>专辑封面（可选）</strong>
          <p>如果文件夹中包含 cover.jpg 或 cover.png，将自动使用；也可以手动上传封面。</p>
        </div>
        <el-upload
          action="#"
          accept=".jpg,.jpeg,.png"
          :auto-upload="false"
          :show-file-list="false"
          :on-change="onCoverChange"
        >
          <el-button :icon="Upload">上传封面</el-button>
        </el-upload>
      </div>

      <div v-if="preparing || uploading" class="process-tip">
        <el-icon class="is-loading"><Loading /></el-icon>
        {{ uploading ? "正在上传专辑文件并解析音频信息，请稍候..." : "正在解析专辑信息，请稍候..." }}
      </div>
    </section>

    <!-- 底部操作条 -->
    <section class="action-bar">
      <el-button
        type="primary"
        size="large"
        class="next-btn"
        :loading="preparing || uploading"
        @click="prepare"
      >
        {{ uploading ? "上传文件中..." : "下一步：解析信息" }}
        <el-icon v-if="!uploading && !preparing" class="next-arrow"><ArrowRight /></el-icon>
      </el-button>
      <div class="action-hints">
        <span><el-icon><CircleCheckFilled /></el-icon>支持整张专辑批量解析</span>
        <span><el-icon><EditPen /></el-icon>提交前可修改曲目、歌手及专辑信息</span>
      </div>
      <div class="selection-status" :class="{ 'has-selection': audioFiles.length > 0 }">
        <IconifyIconOnline icon="ri:folder-3-line" width="16" />
        <span>{{ selectionLabel }}</span>
      </div>
    </section>

    <!-- 步骤 2：解析信息与校正 -->
    <section v-if="prepared" class="panel">
      <div class="panel-heading">
        <span class="panel-badge is-number">2</span>
        <div>
          <h2>解析信息与手动校正</h2>
          <p>已根据音频标签自动填充，请核对后修改，确认无误再一次性入库。</p>
        </div>
      </div>

      <el-alert
        v-if="prepared.warning"
        :title="prepared.warning"
        type="warning"
        show-icon
        :closable="false"
        class="mb-4"
      />

      <el-alert
        v-if="pendingArtistPreview.length"
        type="warning"
        show-icon
        :closable="false"
        class="mb-4"
      >
        <template #title>
          解析发现 {{ pendingArtistPreview.length }} 位歌手不在库中：{{ pendingArtistPreview.join("、") }}
        </template>
        提交时会再次校验，并在创建这些歌手前请你确认；取消则不会创建任何歌手。
      </el-alert>

      <div class="confirm-layout">
        <div class="confirm-cover">
          <el-image
            v-if="prepared.coverUrl || coverPreview"
            :src="prepared.coverUrl || coverPreview"
            fit="cover"
            class="confirm-cover-img"
            :preview-src-list="[prepared.coverUrl || coverPreview]"
            preview-teleported
          />
          <div v-else class="confirm-cover-empty">
            <el-icon><Picture /></el-icon>
          </div>
          <span class="confirm-cover-label">专辑封面</span>
        </div>

        <el-form label-position="top" class="confirm-form">
          <el-row :gutter="18">
            <el-col :xs="24" :md="12">
              <el-form-item label="专辑名称" required>
                <el-input v-model="form.albumName" placeholder="请输入专辑名称" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="主艺人" required>
                <el-select
                  v-model="form.artistId"
                  filterable
                  clearable
                  placeholder="请选择已存在的歌手"
                  class="w-full"
                  @change="onPrimaryArtistChange"
                >
                  <el-option
                    v-for="item in artistOptions"
                    :key="item.artistId"
                    :label="item.artistName"
                    :value="item.artistId"
                  />
                </el-select>
                <div v-if="!form.artistId" class="field-hint">
                  主艺人必须已存在；列表里没有时，请先到「歌手管理」创建。
                </div>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="发行日期">
                <el-date-picker
                  v-model="form.releaseDate"
                  value-format="YYYY-MM-DD"
                  type="date"
                  :disabled-date="disableFutureDate"
                  class="w-full"
                />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="状态">
                <el-input model-value="草稿" disabled />
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="简介">
                <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选，填写专辑简介" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>

      <div class="songs-heading">
        <h3>曲目列表</h3>
        <span>{{ prepared.songs.length }} 首</span>
      </div>
      <el-table :data="prepared.songs" border>
        <el-table-column label="Disc（碟片）" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.discNumber" :min="1" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="曲序" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.trackNumber" :min="1" controls-position="right" />
          </template>
        </el-table-column>
        <el-table-column label="歌曲名" min-width="220">
          <template #default="{ row }"><el-input v-model="row.songName" /></template>
        </el-table-column>
        <el-table-column label="艺人" min-width="240">
          <template #default="{ row }">
            <el-input
              v-model="row.artistName"
              placeholder="多位歌手用 / 或 、 分隔"
              @input="onSongArtistInput(row)"
            />
            <div v-if="parsingCandidates(row).length" class="artist-parsing-cell">
              <el-tag
                v-for="(candidate, index) in parsingCandidates(row)"
                :key="index"
                size="small"
                effect="light"
                :type="candidate.matched ? 'success' : 'danger'"
              >
                {{ candidate.artistName }}{{ candidate.matched ? "" : " · 未匹配" }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="120" prop="durationText" />
        <el-table-column label="文件" min-width="180" prop="fileName" />
      </el-table>

      <div class="actions">
        <el-button @click="clear">清空任务</el-button>
        <el-button type="primary" :loading="committing" :disabled="!canCommit" @click="commit()">
          最终确认并上传
        </el-button>
      </div>
    </section>

    <!-- 确认创建缺失歌手：确认后才写库，取消则本次不上传任何内容 -->
    <el-dialog v-model="pendingConfirmVisible" title="确认创建歌手" width="520px">
      <p class="create-note">
        以下 {{ pendingConfirm?.missingArtists?.length || 0 }} 位歌手在库中不存在，确认后将自动创建，并继续把这张专辑入库：
      </p>
      <div class="create-tags">
        <el-tag
          v-for="name in pendingConfirm?.missingArtists || []"
          :key="name"
          type="warning"
          effect="light"
        >
          {{ name }}
        </el-tag>
      </div>
      <p v-if="pendingConfirm?.ambiguousArtists?.length" class="create-warn">
        另有同名多条记录的歌手，需先到「歌手管理」处理：{{ (pendingConfirm?.ambiguousArtists || []).join("、") }}
      </p>
      <template #footer>
        <el-button :disabled="committing" @click="pendingConfirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="committing" @click="confirmCreateArtists">
          创建并上传
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.album-upload-page {
  min-height: calc(100vh - 120px);
  padding: 20px;
  color: var(--el-text-color-primary);
}

/* ---------- 顶部标题区 ---------- */
.hero-card {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 20px 24px;
  margin-bottom: 14px;
  background: linear-gradient(135deg, rgb(239 246 255 / 95%), rgb(255 255 255 / 98%)),
    var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgb(17 24 39 / 4%);
}

.hero-icon {
  display: grid;
  flex: 0 0 auto;
  width: 58px;
  height: 58px;
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #1d4ed8);
  border-radius: 16px;
  box-shadow: 0 12px 24px rgb(37 99 235 / 24%);
  place-items: center;
}

.hero-copy {
  flex: 1;
  min-width: 0;

  h1 {
    margin: 0 0 5px;
    font-size: 25px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }
}

.hero-back {
  flex: 0 0 auto;
}

/* ---------- 步骤条 ---------- */
.steps-bar {
  padding: 18px 24px 14px;
  margin-bottom: 14px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgb(17 24 39 / 4%);
}

.stepper {
  display: flex;
  align-items: flex-start;
}

.step {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  align-items: center;

  &:not(:first-child)::before {
    position: absolute;
    top: 15px;
    right: calc(50% + 22px);
    left: calc(-50% + 22px);
    height: 2px;
    content: "";
    background: var(--el-border-color);
  }

  &.is-active:not(:first-child)::before,
  &.is-done:not(:first-child)::before {
    background: #bfdbfe;
  }
}

.step-dot {
  display: grid;
  width: 32px;
  height: 32px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-light);
  border: 2px solid var(--el-border-color);
  border-radius: 50%;
  place-items: center;
  transition: 0.25s ease;
}

.step-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.step.is-active .step-dot {
  color: #fff;
  background: #2563eb;
  border-color: #2563eb;
  box-shadow: 0 6px 16px rgb(37 99 235 / 30%);
}

.step.is-active .step-label {
  font-weight: 600;
  color: #2563eb;
}

.step.is-done .step-dot {
  color: #fff;
  background: #2563eb;
  border-color: #2563eb;
}

.step.is-done .step-label {
  color: var(--el-text-color-regular);
}

/* ---------- 面板 ---------- */
.panel {
  padding: 20px;
  margin-bottom: 14px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgb(17 24 39 / 4%);
}

.panel-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;

  h2 {
    margin: 0;
    font-size: 17px;
  }

  p {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
}

.panel-badge {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  color: #2563eb;
  background: rgb(37 99 235 / 10%);
  border-radius: 10px;
  place-items: center;

  &.is-number {
    font-weight: 700;
    color: #fff;
    background: #2563eb;
    border-radius: 50%;
  }
}

/* ---------- 拖拽上传区 ---------- */
.hidden-input {
  display: none;
}

.dropzone {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  padding: 40px 24px 28px;
  text-align: center;
  cursor: pointer;
  background: linear-gradient(180deg, rgb(248 250 255 / 90%), rgb(255 255 255 / 40%));
  border: 1.5px dashed var(--el-border-color);
  border-radius: 14px;
  outline: none;
  transition: 0.22s ease;

  &:hover,
  &:focus-visible {
    border-color: #3b82f6;
    box-shadow: 0 10px 26px rgb(59 130 246 / 10%);
  }

  &.is-dragging {
    background: rgb(239 246 255 / 90%);
    border-color: #2563eb;
    border-style: solid;
    box-shadow: 0 14px 32px rgb(37 99 235 / 16%);
    transform: scale(1.005);
  }

  strong {
    font-size: 16px;
    color: var(--el-text-color-primary);
  }
}

.drop-icon {
  display: grid;
  width: 72px;
  height: 72px;
  margin-bottom: 4px;
  color: #4f46e5;
  background: linear-gradient(135deg, rgb(99 102 241 / 14%), rgb(59 130 246 / 12%));
  border-radius: 20px;
  place-items: center;
}

.drop-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.dropzone :deep(.el-button) {
  margin-top: 6px;
}

.drop-hint {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* ---------- 封面 ---------- */
.cover-section {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 16px;
  margin-top: 16px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}

.cover-thumb {
  display: grid;
  flex: 0 0 auto;
  width: 64px;
  height: 64px;
  overflow: hidden;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color);
  border-radius: 10px;
  place-items: center;
}

.cover-img {
  width: 64px;
  height: 64px;
}

.cover-placeholder {
  font-size: 24px;
}

.cover-copy {
  flex: 1;
  min-width: 0;

  strong {
    display: block;
    margin-bottom: 4px;
    font-size: 14px;
  }

  p {
    margin: 0;
    font-size: 13px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }
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

/* ---------- 底部操作条 ---------- */
.action-bar {
  position: sticky;
  bottom: 12px;
  z-index: 6;
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 14px 20px;
  margin-bottom: 14px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 12px 32px rgb(17 24 39 / 10%);
}

.next-btn {
  flex: 0 0 auto;
}

.next-arrow {
  margin-left: 4px;
}

.action-hints {
  display: flex;
  flex: 1;
  gap: 22px;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);

  span {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    white-space: nowrap;
  }

  .el-icon {
    color: var(--el-text-color-placeholder);
  }
}

.selection-status {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 260px;
  padding: 7px 12px;
  overflow: hidden;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  background: var(--el-fill-color-light);
  border-radius: 8px;

  span {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &.has-selection {
    color: #2563eb;
    background: rgb(37 99 235 / 10%);
  }
}

/* ---------- 步骤 2 ---------- */
.draft-panel {
  padding: 14px 18px;
}

.draft-head {
  display: flex;
  gap: 8px;
  align-items: center;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.draft-panel.is-success .draft-head {
  color: #15803d;
}

.draft-note {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.draft-empty {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.draft-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
}

.draft-files {
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.draft-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
}

.draft-expiry {
  margin-right: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.draft-expiry.is-soon {
  color: var(--el-color-warning);
}

.confirm-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 26px;
}

.confirm-cover {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.confirm-cover-img,
.confirm-cover-empty {
  width: 180px;
  height: 180px;
  border-radius: 12px;
  box-shadow: 0 12px 26px rgb(15 23 42 / 12%);
}

.confirm-cover-empty {
  display: grid;
  font-size: 32px;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-lighter);
  place-items: center;
}

.confirm-cover-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.confirm-form {
  min-width: 0;
}

/* ---------- 歌手确认 ---------- */
.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning);
}

.artist-parsing-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.create-note {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.create-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.create-warn {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-danger);
}

.songs-heading {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin: 24px 0 12px;

  h3 {
    margin: 0;
    font-size: 15px;
  }

  span {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .album-upload-page {
    padding: 14px;
  }

  .hero-card {
    flex-wrap: wrap;
  }

  .hero-copy {
    flex: 1 1 60%;
  }

  .hero-back {
    order: 3;
    width: 100%;
  }

  .action-bar {
    flex-wrap: wrap;
  }

  .next-btn {
    width: 100%;
  }

  .action-hints {
    flex-wrap: wrap;
    gap: 10px 18px;
  }

  .selection-status {
    max-width: 100%;
  }

  .confirm-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
