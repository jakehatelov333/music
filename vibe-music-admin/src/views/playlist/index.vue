<script setup lang="ts">
import { ref } from "vue";
import { usePlaylist } from "./utils/hook";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";

import Upload from "@iconify-icons/ri/upload-line";
import More from "@iconify-icons/ep/more-filled";
import Delete from "@iconify-icons/ep/delete";
import EditPen from "@iconify-icons/ep/edit-pen";
import Refresh from "@iconify-icons/ep/refresh";
import AddFill from "@iconify-icons/ri/add-circle-line";
import MusicFill from "@iconify-icons/ri/music-2-fill";
import SendPlaneFill from "@iconify-icons/ri/send-plane-fill";
import InboxUnarchive from "@iconify-icons/ri/inbox-unarchive-line";

defineOptions({
  name: "PlaylistManagement"
});

const formRef = ref();
const tableRef = ref();

const {
  form,
  loading,
  columns,
  dataList,
  selectedNum,
  pagination,
  buttonClass,
  deviceDetection,
  onSearch,
  resetForm,
  onbatchDel,
  openDialog,
  openSongs,
  handleDelete,
  handlePublish,
  handleUnpublish,
  handleUpload,
  handleSizeChange,
  handleSortChange,
  onSelectionCancel,
  handleCurrentChange,
  handleSelectionChange
} = usePlaylist(tableRef);
</script>

<template>
  <div :class="['flex', 'justify-between', deviceDetection() && 'flex-wrap']">
    <div
      :class="[deviceDetection() ? ['w-full', 'mt-2'] : 'w-[calc(100%-0px)]']"
    >
      <el-form
        ref="formRef"
        :inline="true"
        :model="form"
        class="search-form bg-bg_color w-[99/100] pl-8 pt-[12px] overflow-auto"
      >
        <el-form-item label="歌单：" prop="title">
          <el-input
            v-model="form.title"
            placeholder="请输入歌单名称"
            clearable
            class="!w-[180px]"
          />
        </el-form-item>
        <el-form-item label="风格：" prop="style">
          <el-input
            v-model="form.style"
            placeholder="请输入风格"
            clearable
            class="!w-[150px]"
          />
        </el-form-item>
        <el-form-item label="来源：" prop="source">
          <el-select
            v-model="form.source"
            placeholder="全部"
            clearable
            class="!w-[140px]"
          >
            <el-option label="平台歌单" value="PLATFORM" />
            <el-option label="用户歌单" value="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="发布状态：" prop="status">
          <el-select
            v-model="form.status"
            placeholder="全部"
            clearable
            class="!w-[140px]"
          >
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已发布" value="PUBLISHED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :icon="useRenderIcon('ri:search-line')"
            :loading="loading"
            @click="onSearch"
          >
            搜索
          </el-button>
          <el-button :icon="useRenderIcon(Refresh)" @click="resetForm(formRef)">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <PureTableBar title="歌单管理" :columns="columns" @refresh="onSearch">
        <template #buttons>
          <el-button
            type="primary"
            :icon="useRenderIcon(AddFill)"
            @click="openDialog()"
          >
            新增歌单
          </el-button>
        </template>
        <template v-slot="{ size, dynamicColumns }">
          <div
            v-if="selectedNum > 0"
            v-motion-fade
            class="bg-[var(--el-fill-color-light)] w-full h-[46px] mb-2 pl-4 flex items-center"
          >
            <div class="flex-auto">
              <span
                style="font-size: var(--el-font-size-base)"
                class="text-[rgba(42,46,54,0.5)] dark:text-[rgba(220,220,242,0.5)]"
              >
                已选 {{ selectedNum }} 项
              </span>
              <el-button type="primary" text @click="onSelectionCancel">
                取消选择
              </el-button>
            </div>
            <el-popconfirm title="是否确认删除?" @confirm="onbatchDel">
              <template #reference>
                <el-button type="danger" text class="mr-1">
                  批量删除
                </el-button>
              </template>
            </el-popconfirm>
          </div>
          <pure-table
            ref="tableRef"
            row-key="playlistId"
            adaptive
            :adaptiveConfig="{ offsetBottom: 108 }"
            align-whole="center"
            table-layout="auto"
            :loading="loading"
            :size="size"
            :data="dataList"
            :columns="dynamicColumns"
            :pagination="{ ...pagination, size }"
            :header-cell-style="{
              background: 'var(--el-fill-color-light)',
              color: 'var(--el-text-color-primary)'
            }"
            @selection-change="handleSelectionChange"
            @sort-change="handleSortChange"
            @page-size-change="handleSizeChange"
            @page-current-change="handleCurrentChange"
          >
            <template #operation="{ row }">
              <el-button
                v-if="!row.ownerUserId"
                class="reset-margin"
                link
                type="primary"
                :size="size"
                :icon="useRenderIcon(EditPen)"
                @click="openDialog('修改', row)"
              >
                修改
              </el-button>
              <el-popconfirm
                :title="`是否确认删除歌单编号为 ${row.playlistId} 的这条数据`"
                @confirm="handleDelete(row)"
              >
                <template #reference>
                  <el-button
                    class="reset-margin"
                    link
                    type="primary"
                    :size="size"
                    :icon="useRenderIcon(Delete)"
                  >
                    删除
                  </el-button>
                </template>
              </el-popconfirm>
              <el-dropdown>
                <el-button
                  class="ml-3 mt-[2px]"
                  link
                  type="primary"
                  :size="size"
                  :icon="useRenderIcon(More)"
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item>
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(MusicFill)"
                        @click="openSongs(row)"
                      >
                        {{ row.ownerUserId ? "查看歌曲" : "歌曲维护" }}
                      </el-button>
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="!row.ownerUserId && row.status !== 'PUBLISHED'"
                    >
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(SendPlaneFill)"
                        @click="handlePublish(row)"
                      >
                        发布
                      </el-button>
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="!row.ownerUserId && row.status === 'PUBLISHED'"
                    >
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(InboxUnarchive)"
                        @click="handleUnpublish(row)"
                      >
                        撤回草稿
                      </el-button>
                    </el-dropdown-item>
                    <el-dropdown-item v-if="!row.ownerUserId">
                      <el-button
                        :class="buttonClass"
                        link
                        type="primary"
                        :size="size"
                        :icon="useRenderIcon(Upload)"
                        @click="handleUpload(row)"
                      >
                        上传封面
                      </el-button>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </pure-table>
        </template>
      </PureTableBar>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.el-dropdown-menu__item i) {
  margin: 0;
}

:deep(.el-button:focus-visible) {
  outline: none;
}

.main-content {
  margin: 24px 24px 0 !important;
}

.search-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
  }
}
</style>
