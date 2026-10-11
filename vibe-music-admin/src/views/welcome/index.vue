<script setup lang="ts">
import { computed } from "vue";
import { useDark } from "./utils";
import { ReNormalCountTo } from "@/components/ReCountTo";
import { ChartBar, ChartPie1, ChartPie2 } from "./components/charts";
import useChartData from "./hooks/useChartData";
import GroupLine from "@iconify-icons/ri/group-line";
import Artist from "@iconify-icons/ri/mic-fill";
import Music from "@iconify-icons/ri/music-2-fill";
import Album from "@iconify-icons/ri/album-fill";
import Playlist from "@iconify-icons/ri/play-list-fill";

defineOptions({
  name: "Welcome"
});

const { isDark } = useDark();

const {
  userCount,
  artistCount,
  songCount,
  playlistCount,
  albumCount,
  westernPopCount,
  chinesePopCount,
  cantonesePopCount,
  koreanPopCount,
  classicCount,
  hiphopCount,
  rockCount,
  electronicCount,
  jazzCount,
  lightCount,
  countAmerica,
  countChina,
  countKorea,
  countJapan,
  countGermany,
  countBritain,
  maleCount,
  femaleCount
} = useChartData();

const stats = computed(() => [
  {
    label: "用户数量",
    value: userCount.value,
    icon: GroupLine,
    color: "#41b6ff",
    background: "#effaff"
  },
  {
    label: "歌手数量",
    value: artistCount.value,
    icon: Artist,
    color: "#e85f33",
    background: "#fff5f4"
  },
  {
    label: "歌曲数量",
    value: songCount.value,
    icon: Music,
    color: "#26ce83",
    background: "#eff8f4"
  },
  {
    label: "专辑数量",
    value: albumCount.value,
    icon: Album,
    color: "#f5a623",
    background: "#fff8e8"
  },
  {
    label: "歌单数量",
    value: playlistCount.value,
    icon: Playlist,
    color: "#7846e5",
    background: "#f6f4fe"
  }
]);

const pieChartData1 = computed(() => [
  { value: westernPopCount.value, name: "欧美流行" },
  { value: chinesePopCount.value, name: "华语流行" },
  { value: cantonesePopCount.value, name: "粤语流行" },
  { value: koreanPopCount.value, name: "韩国流行" },
  { value: classicCount.value, name: "古典" },
  { value: hiphopCount.value, name: "嘻哈说唱" },
  { value: rockCount.value, name: "摇滚" },
  { value: electronicCount.value, name: "电子" },
  { value: jazzCount.value, name: "节奏布鲁斯" },
  { value: lightCount.value, name: "轻音乐" }
]);

const pieChartData2 = computed(() => [
  { value: maleCount.value, name: "男" },
  { value: femaleCount.value, name: "女" }
]);
</script>

<template>
  <div class="dashboard-page">
    <header class="dashboard-heading">
      <div>
        <h1>数据概览</h1>
        <p>Vibe Music 内容运营数据</p>
      </div>
    </header>

    <section class="metric-grid" aria-label="内容统计">
      <article
        v-for="stat in stats"
        :key="stat.label"
        class="metric-card"
      >
        <div
          class="metric-icon"
          :style="{
            color: stat.color,
            backgroundColor: isDark ? 'transparent' : stat.background
          }"
        >
          <IconifyIconOffline :icon="stat.icon" width="21" />
        </div>
        <div class="metric-content">
          <p class="metric-label">{{ stat.label }}</p>
          <ReNormalCountTo
            :duration="1800"
            :fontSize="'2rem'"
            :startVal="0"
            :endVal="stat.value"
          />
        </div>
      </article>
    </section>

    <section class="analysis-grid">
      <el-card class="analysis-card analysis-card--primary" shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <h2>歌曲类型比例</h2>
              <p>各音乐类型的歌曲数量分布</p>
            </div>
          </div>
        </template>
        <ChartPie1 :chartData="pieChartData1" />
      </el-card>

      <div class="analysis-side">
        <el-card class="analysis-card" shadow="never">
          <template #header>
            <div class="card-heading">
              <div>
                <h2>歌手国籍分布</h2>
                <p>主要地区歌手数量</p>
              </div>
            </div>
          </template>
          <ChartBar
            :barChartData="[
              countAmerica,
              countChina,
              countKorea,
              countJapan,
              countGermany,
              countBritain
            ]"
          />
        </el-card>

        <el-card class="analysis-card" shadow="never">
          <template #header>
            <div class="card-heading">
              <div>
                <h2>歌手性别比例</h2>
                <p>男女歌手构成</p>
              </div>
            </div>
          </template>
          <ChartPie2 :chartData="pieChartData2" />
        </el-card>
      </div>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-bottom: 20px;
}

.dashboard-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 4px 2px 0;

  h1 {
    margin: 0;
    color: var(--el-text-color-primary);
    font-size: 24px;
    font-weight: 650;
    letter-spacing: -0.02em;
  }

  p {
    margin: 6px 0 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  display: flex;
  min-width: 0;
  min-height: 108px;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.045);
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.metric-card:hover {
  border-color: var(--el-border-color);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.075);
  transform: translateY(-2px);
}

.metric-icon {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
}

.metric-content {
  min-width: 0;
}

.metric-label {
  margin: 0 0 2px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(320px, 0.85fr);
  gap: 18px;
  align-items: start;
}

.analysis-side {
  display: grid;
  gap: 18px;
}

.analysis-card {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.045);

  :deep(.el-card__header) {
    padding: 18px 20px 0;
    border-bottom: 0;
  }

  :deep(.el-card__body) {
    padding: 10px 18px 18px;
  }
}

.card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;

  h2 {
    margin: 0;
    color: var(--el-text-color-primary);
    font-size: 16px;
    font-weight: 600;
  }

  p {
    margin: 5px 0 0;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1180px) {
  .analysis-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .analysis-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .analysis-side {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 560px) {
  .metric-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

:deep(.el-card) {
  --el-card-border-color: transparent;

  .el-scrollbar__bar {
    display: none;
  }
}
</style>
