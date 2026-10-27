<script setup lang="ts">
import { onMounted, ref } from "vue";
import ReCol from "@/components/ReCol";
import { getArtistList } from "@/api/system";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";

const props = withDefaults(defineProps<FormProps>(), {
  formInline: () => ({
    title: "新增",
    artistId: null,
    artistName: "",
    artistIds: [] as number[],
    artists: [] as { artistId: number; artistName: string }[],
    songId: null,
    songName: "",
    album: "",
    style: [],
    releaseTime: null,
    instrumental: false
  })
});

const styleOptions = [
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
const ruleFormRef = ref();
const newFormInline = ref(props.formInline);

const artistOptions = ref<{ artistId: number; artistName: string }[]>([]);
const artistSearching = ref(false);

/**
 * 远程搜索歌手。多选值绑定 artistId，顺序即展示顺序（第一个为主艺人）。
 * 已选中的歌手会被并入候选，保证标签始终能显示名字。
 */
async function searchArtists(query: string) {
  artistSearching.value = true;
  try {
    const res = await getArtistList({
      pageNum: 1,
      pageSize: 50,
      ...(query?.trim() ? { artistName: query.trim() } : {})
    });
    const items = (res.data?.items || []).map((item: any) => ({
      artistId: item.artistId as number,
      artistName: item.artistName as string
    }));
    const merged = new Map(artistOptions.value.map(item => [item.artistId, item]));
    items.forEach(item => merged.set(item.artistId, item));
    artistOptions.value = [...merged.values()];
  } catch {
    // 忽略：搜索失败时仍可用已选中的候选
  } finally {
    artistSearching.value = false;
  }
}

/** 保持 artistId 与多选第一位一致：专辑归属与旧接口都依赖它。 */
function handleArtistIdsChange(ids: number[]) {
  newFormInline.value.artistId = ids.length ? ids[0] : null;
}

function getRef() {
  return ruleFormRef.value;
}

defineExpose({ getRef });

onMounted(() => {
  // 先用表单里带回的歌手填候选，再拉一页做兜底，避免标签显示成裸 id。
  const seeded = (newFormInline.value.artists || []).filter(
    item => item?.artistId
  );
  if (seeded.length) {
    artistOptions.value = [...seeded];
  }
  searchArtists("");
});
</script>

<template>
  <el-form
    ref="ruleFormRef"
    :model="newFormInline"
    :rules="formRules"
    label-width="82px"
  >
    <el-row :gutter="30">
      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌手编号" prop="artistId">
          <el-input
            v-model="newFormInline.artistId"
            clearable
            disabled
            placeholder="newFormInline.artistId"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌手" prop="artistIds" required>
          <el-select
            v-model="newFormInline.artistIds"
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
            />
          </el-select>
          <div class="artist-hint">
            第一位是主艺人（决定专辑归属）。展示为「{{ newFormInline.artistName || "—" }}」
          </div>
        </el-form-item>
      </re-col>

      <re-col
        v-if="newFormInline.title === '修改'"
        :value="12"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="歌曲编号" prop="songId">
          <el-input
            v-model="newFormInline.songId"
            clearable
            disabled
            placeholder="newFormInline.songId"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="歌名" prop="songName" required>
          <el-input
            v-model="newFormInline.songName"
            clearable
            placeholder="请输入歌名"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="专辑" prop="album" required>
          <el-input
            v-model="newFormInline.album"
            clearable
            placeholder="请输入专辑"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="发行日期" prop="releaseTime" required>
          <el-date-picker
            v-model="newFormInline.releaseTime"
            type="date"
            placeholder="请选择发行日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="曲风">
          <el-select
            v-model="newFormInline.style"
            placeholder="请选择曲风"
            class="w-full"
            clearable
            multiple
          >
            <el-option
              v-for="(item, index) in styleOptions"
              :key="index"
              :label="item"
              :value="item"
            >
              {{ item }}
            </el-option>
          </el-select>
        </el-form-item>
      </re-col>

      <re-col
        :value="24"
        :xs="24"
        :sm="24"
      >
        <el-form-item label="曲目类型">
          <el-checkbox v-model="newFormInline.instrumental">
            这是伴奏 / 无需歌词
          </el-checkbox>
          <div class="lyric-hint">
            标记后会作为“歌词已处理”保存，并从后续批量搜索中跳过；用户端会显示伴奏提示。
          </div>
        </el-form-item>
      </re-col>

    </el-row>
  </el-form>
</template>

<style scoped>
.artist-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.lyric-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
