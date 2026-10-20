<script setup lang="ts">
import { ref, computed } from "vue";
import { ElMessage } from "element-plus";
import ReCol from "@/components/ReCol";
import { formRules } from "../utils/rule";
import { FormProps } from "../utils/types";

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
    avatarPreview: ""
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
}

function clearAvatar() {
  if (newFormInline.value.avatarPreview) {
    URL.revokeObjectURL(newFormInline.value.avatarPreview);
  }
  newFormInline.value.avatarFile = null;
  newFormInline.value.avatarPreview = "";
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
                    {{ newFormInline.avatarPreview ? "更换图片" : "选择图片" }}
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
