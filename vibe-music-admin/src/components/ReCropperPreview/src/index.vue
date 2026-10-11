<script setup lang="tsx">
import { onBeforeUnmount, ref, watch } from "vue";
import { ElMessage, type UploadFile } from "element-plus";
import ReCropper from "@/components/ReCropper";
import { formatBytes } from "@pureadmin/utils";

defineOptions({
  name: "ReCropperPreview"
});

const props = defineProps({
  imgSrc: String,
  uploadable: {
    type: Boolean,
    default: false
  },
  circled: {
    type: Boolean,
    default: true
  }
});

const emit = defineEmits(["cropper"]);

const infos = ref();
const popoverRef = ref();
const refCropper = ref();
const showPopover = ref(false);
const cropperImg = ref<string>("");
const currentImgSrc = ref(props.imgSrc || "");
const cropperKey = ref(0);
const localImgSrc = ref("");

watch(
  () => props.imgSrc,
  value => {
    if (!localImgSrc.value) {
      currentImgSrc.value = value || "";
      cropperKey.value += 1;
      showPopover.value = false;
    }
  }
);

function onCropper({ base64, blob, info }) {
  infos.value = info;
  cropperImg.value = base64;
  emit("cropper", { base64, blob, info });
}

function handleSelect(uploadFile: UploadFile) {
  const raw = uploadFile.raw;
  if (!raw) return;
  if (!["image/jpeg", "image/png", "image/webp"].includes(raw.type)) {
    ElMessage.warning("仅支持 JPG、PNG、WebP 图片");
    return;
  }
  if (raw.size > 10 * 1024 * 1024) {
    ElMessage.warning("图片大小不能超过 10MB");
    return;
  }

  clearLocalImg();
  localImgSrc.value = URL.createObjectURL(raw);
  currentImgSrc.value = localImgSrc.value;
  cropperKey.value += 1;
  showPopover.value = false;
  infos.value = undefined;
  cropperImg.value = "";
  emit("cropper", null);
}

function clearLocalImg() {
  if (localImgSrc.value?.startsWith("blob:")) {
    URL.revokeObjectURL(localImgSrc.value);
  }
  localImgSrc.value = "";
}

function hidePopover() {
  popoverRef.value?.hide();
}

onBeforeUnmount(clearLocalImg);

defineExpose({ hidePopover });
</script>

<template>
  <div v-loading="!showPopover" element-loading-background="transparent">
    <el-upload
      v-if="uploadable"
      action="#"
      accept=".jpg,.jpeg,.png,.webp"
      :auto-upload="false"
      :show-file-list="false"
      :on-change="handleSelect"
      class="mb-3 text-center"
    >
      <el-button type="primary" plain>选择本地图片</el-button>
    </el-upload>

    <el-popover
      ref="popoverRef"
      :visible="showPopover"
      placement="right"
      width="18vw"
    >
      <template #reference>
        <div class="w-[18vw]">
          <ReCropper
            :key="cropperKey"
            ref="refCropper"
            :src="currentImgSrc"
            :circled="circled"
            @cropper="onCropper"
            @readied="showPopover = true"
          />
          <p v-show="showPopover" class="mt-1 text-center">
            温馨提示：右键上方裁剪区可开启功能菜单
          </p>
        </div>
      </template>
      <div class="flex flex-wrap justify-center items-center text-center">
        <el-image
          v-if="cropperImg"
          :src="cropperImg"
          :preview-src-list="Array.of(cropperImg)"
          fit="cover"
        />
        <div v-if="infos" class="mt-1">
          <p>
            图像大小：{{ parseInt(infos.width) }} ×
            {{ parseInt(infos.height) }}像素
          </p>
          <p>
            文件大小：{{ formatBytes(infos.size) }}（{{ infos.size }} 字节）
          </p>
        </div>
      </div>
    </el-popover>
  </div>
</template>
