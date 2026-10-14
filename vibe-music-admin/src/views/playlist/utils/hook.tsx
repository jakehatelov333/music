import "./reset.css";
import editForm from "../form/index.vue";
import songsForm from "../form/songs.vue";
import { message } from "@/utils/message";
import playlistCover from "@/assets/song.jpg";
import { addDialog } from "@/components/ReDialog";
import type { PaginationProps } from "@pureadmin/table";
import ReCropperPreview from "@/components/ReCropperPreview";
import type { FormItemProps } from "../utils/types";
import { getKeyList, deviceDetection } from "@pureadmin/utils";
import {
  getPlaylistList,
  addPlaylist,
  updatePlaylist,
  updatePlaylistCover,
  deletePlaylist,
  deletePlaylists,
  publishPlaylist,
  unpublishPlaylist
} from "@/api/system";
import { type Ref, h, ref, toRaw, computed, reactive, onMounted } from "vue";
import { ElMessageBox } from "element-plus";

export function usePlaylist(tableRef: Ref) {
  const form = reactive({
    pageNum: 1,
    pageSize: 10,
    title: null,
    style: null,
    // 发布状态与来源是两个独立维度，分别筛选
    status: null,
    source: null,
    // 服务端排序：sortField 由列 prop 映射（白名单校验在后端），order 为 asc/desc
    sortField: null,
    order: null
  });
  const formRef = ref();
  const dataList = ref([]);
  const loading = ref(true);
  // 上传歌单封面信息
  const coverInfo = ref();
  const selectedNum = ref(0);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 10,
    currentPage: 1,
    background: true
  });
  const columns: TableColumnList = [
    {
      label: "勾选列", // 如果需要表格多选，此处label必须设置
      type: "selection",
      fixed: "left",
      reserveSelection: true // 数据刷新后保留选项
    },
    {
      label: "歌单编号",
      prop: "playlistId",
      width: 90,
      className: "nowrap-col",
      sortable: "custom"
    },
    {
      label: "封面",
      prop: "cover",
      cellRenderer: ({ row }) => (
        <el-image
          fit="cover"
          preview-teleported={true}
          src={row.cover || playlistCover}
          preview-src-list={Array.of(row.cover || playlistCover)}
          class="w-[72px] h-[72px] rounded-lg align-middle"
        />
      ),
      width: 90
    },
    {
      label: "歌单",
      prop: "title",
      minWidth: 200
    },
    {
      label: "风格",
      prop: "style",
      minWidth: 130,
      cellRenderer: ({ row }) => (row.style ? <el-tag>{row.style}</el-tag> : "")
    },
    {
      label: "来源",
      prop: "ownerName",
      width: 130,
      cellRenderer: ({ row }) =>
        row.ownerUserId ? (
          <el-tag type="warning">用户 · {row.ownerName || row.ownerUserId}</el-tag>
        ) : (
          <el-tag type="info">平台</el-tag>
        )
    },
    {
      label: "发布状态",
      prop: "status",
      width: 100,
      className: "nowrap-col",
      cellRenderer: ({ row }) => (
        <el-tag type={row.status === "PUBLISHED" ? "success" : "info"}>
          {row.status === "PUBLISHED" ? "已发布" : "草稿"}
        </el-tag>
      )
    },
    {
      label: "可见性",
      prop: "isPublic",
      width: 90,
      className: "nowrap-col",
      cellRenderer: ({ row }) => (
        <el-tag type={row.isPublic ? "primary" : "warning"}>
          {row.isPublic ? "公开" : "私人"}
        </el-tag>
      )
    },
    {
      label: "歌曲数",
      prop: "songCount",
      width: 90,
      align: "center",
      className: "nowrap-col",
      sortable: "custom"
    },
    {
      label: "简介",
      prop: "introduction",
      minWidth: 240,
      width: 320,
      formatter: ({ introduction }) => {
        if (!introduction) {
          return "";
        }
        if (typeof introduction !== "string") {
          return "无效数据";
        }
        const maxChars = 60; // 两行最多60个字符
        if (introduction.length > maxChars) {
          return introduction.slice(0, maxChars) + "...";
        }
        return introduction;
      }
    },
    {
      label: "操作",
      fixed: "right",
      width: 210,
      slot: "operation"
    }
  ];
  const buttonClass = computed(() => {
    return [
      "!h-[20px]",
      "reset-margin",
      "!text-gray-500",
      "dark:!text-white",
      "dark:hover:!text-primary"
    ];
  });

  function handleDelete(row) {
    deletePlaylist(row.playlistId).then(res => {
      if (res.code === 0) {
        message(`您删除了歌单编号为 ${row.playlistId} 的这条数据`, {
          type: "success"
        });
        onSearch();
      } else {
        message(res.message || "删除歌单失败", { type: "error" });
      }
    });
  }

  function handleSizeChange(val: number) {
    pagination.pageSize = val; // 更新每页条目数
    form.pageSize = val; // 更新页码参数
    onSearch(); // 重新获取数据
  }

  function handleCurrentChange(val: number) {
    pagination.currentPage = val; // 更新当前页码
    form.pageNum = val; // 更新页码参数
    onSearch(); // 重新获取数据
  }

  /** 列头排序（服务端排序）：升序→降序→取消循环，取消后回默认排序 */
  function handleSortChange({ prop, order }) {
    if (order === "ascending") {
      form.sortField = prop;
      form.order = "asc";
    } else if (order === "descending") {
      form.sortField = prop;
      form.order = "desc";
    } else {
      form.sortField = null;
      form.order = null;
    }
    pagination.currentPage = 1;
    form.pageNum = 1;
    onSearch();
  }

  /** 当CheckBox选择项发生变化时会触发该事件 */
  function handleSelectionChange(val) {
    selectedNum.value = val.length;
    // 重置表格高度
    tableRef.value.setAdaptive();
  }

  /** 取消选择 */
  function onSelectionCancel() {
    selectedNum.value = 0;
    // 用于多选表格，清空歌单的选择
    tableRef.value.getTableRef().clearSelection();
  }

  /** 批量删除 */
  function onbatchDel() {
    // 返回当前选中的行
    const curSelected = tableRef.value.getTableRef().getSelectionRows();
    const ids = getKeyList(curSelected, "playlistId");

    deletePlaylists(ids)
      .then(res => {
        if (res.code === 0) {
          message(`已删除歌单编号为 ${ids} 的数据`, {
            type: "success"
          });
          tableRef.value.getTableRef().clearSelection();
          onSearch();
        } else {
          message(res.message || "删除歌单失败", { type: "error" });
        }
      })
      .catch(err => {
        console.error("删除歌单失败", err);
        message("删除歌单失败", { type: "error" });
      });
  }

  async function onSearch() {
    try {
      const result = await getPlaylistList(toRaw(form)); // 获取完整的返回数据

      if (result.code === 0 && result.data && result.data.items) {
        // 如果返回状态码为 0（成功），且 data 和 items 存在
        pagination.total = result.data.total; // 设置总条目数
        dataList.value = result.data.items.map(item => ({
          playlistId: item.playlistId,
          title: item.title,
          cover: item.coverUrl,
          style: item.style,
          introduction: item.introduction,
          status: item.status,
          isPublic: item.isPublic,
          coverIsCustom: item.coverIsCustom,
          songCount: item.songCount ?? 0,
          ownerUserId: item.ownerUserId,
          ownerName: item.ownerName
        }));
      } else {
        // 如果返回状态码不为 0 或 data.items 不存在
        dataList.value = [];
        pagination.total = 0;
        message("未找到匹配的歌单信息", { type: "warning" });
      }
    } catch (error) {
      console.error("请求失败：", error);
      message("会话过期，请重新登录", { type: "error" });
    }

    setTimeout(() => {
      loading.value = false;
    }, 500);
  }

  const resetForm = formEl => {
    if (!formEl) return;
    formEl.resetFields();
    form.pageNum = 1;
    pagination.currentPage = 1;
    // 排序不属于表单项，resetFields 不会清掉，手动还原为默认排序
    form.sortField = null;
    form.order = null;
    onSearch();
  };

  function openDialog(formTitle = "新增", row?: FormItemProps) {
    addDialog({
      title: `${formTitle}歌单`,
      props: {
        formInline: {
          formTitle,
          playlistId: row?.playlistId ?? "",
          title: row?.title ?? "",
          style: row?.style ?? "",
          introduction: row?.introduction ?? "",
          songIds: []
        }
      },
      width: "52%",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () => h(editForm, { ref: formRef, formInline: null }),
      beforeSure: (done, { options }) => {
        const FormRef = formRef.value.getRef();
        const curData = options.props.formInline as FormItemProps;
        function chores() {
          message(`您${formTitle}了歌单为 ${curData.title} 的这条数据`, {
            type: "success"
          });
          done(); // 关闭弹框
          onSearch(); // 刷新表格数据
        }
        FormRef.validate(valid => {
          if (valid) {
            // 表单规则校验通过
            if (formTitle === "新增") {
              addPlaylist(curData).then(res => {
                if (res.code === 0) {
                  message(
                    `歌单已创建为草稿，共 ${curData.songIds?.length ?? 0} 首歌曲，发布后即可公开`,
                    { type: "info" }
                  );
                  chores();
                } else {
                  message("新增歌单失败，" + res.message, { type: "error" });
                }
              });
            } else {
              updatePlaylist(curData).then(res => {
                if (res.code === 0) {
                  chores();
                } else {
                  message("修改歌单失败，" + res.message, { type: "error" });
                }
              });
            }
          }
        });
      }
    });
  }

  /** 维护歌单歌曲：查看详情、添加、移除；用户自建歌单只读 */
  function openSongs(row) {
    addDialog({
      title: `歌单歌曲 - ${row.title}`,
      width: "62%",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      hideFooter: true,
      contentRenderer: () =>
        h(songsForm, {
          playlistId: row.playlistId,
          playlistTitle: row.title,
          readonly: !!row.ownerUserId,
          onChanged: () => onSearch()
        })
    });
  }

  /** 发布歌单：平台歌单固定公开 */
  async function handlePublish(row) {
    try {
      await ElMessageBox.confirm(
        `发布歌单「${row.title}」？发布后即为公开，所有用户可见。`,
        "发布歌单",
        { confirmButtonText: "发布", cancelButtonText: "取消", type: "warning" }
      );
    } catch {
      return;
    }
    publishPlaylist(row.playlistId).then(res => {
      if (res.code === 0) {
        message(`歌单「${row.title}」已发布并公开`, { type: "success" });
        onSearch();
      } else {
        message(res.message || "发布歌单失败", { type: "error" });
      }
    });
  }

  /** 撤回为草稿：普通用户将无法再访问 */
  async function handleUnpublish(row) {
    try {
      await ElMessageBox.confirm(
        `撤回歌单「${row.title}」为草稿？撤回后普通用户将无法访问。`,
        "撤回草稿",
        { confirmButtonText: "撤回", cancelButtonText: "取消", type: "warning" }
      );
    } catch {
      return;
    }
    unpublishPlaylist(row.playlistId).then(res => {
      if (res.code === 0) {
        message(`歌单「${row.title}」已撤回为草稿`, { type: "success" });
        onSearch();
      } else {
        message(res.message || "撤回歌单失败", { type: "error" });
      }
    });
  }

  const cropRef = ref();
  /** 上传歌单封面 */
  async function handleUpload(row) {
    addDialog({
      title: "裁剪、上传歌单封面",
      width: "40%",
      closeOnClickModal: false,
      fullscreen: deviceDetection(),
      contentRenderer: () =>
        h(ReCropperPreview, {
          ref: cropRef,
          uploadable: true,
          circled: false,
          imgSrc: row.cover || playlistCover,
          onCropper: info => (coverInfo.value = info)
        }),
      beforeSure: async done => {
        if (!coverInfo.value?.blob) {
          message("图片裁剪失败，请重试", { type: "error" });
          return;
        }

        // 创建 FormData
        const formData = new FormData();
        formData.append("cover", coverInfo.value.blob); // 使用 blob 数据
        formData.append("playlistId", row.playlistId); // 传递歌单 ID

        try {
          // 调用上传接口
          const res = await updatePlaylistCover(row.playlistId, formData);

          if (res.code === 0) {
            message("上传歌单封面成功", { type: "success" });
            done(); // 关闭弹框
            onSearch(); // 刷新表格数据
          } else {
            message(res.message || "上传歌单封面失败", { type: "error" });
          }
        } catch (error) {
          console.error("上传失败:", error);
          message("上传失败，请重试", { type: "error" });
        }
      },
      closeCallBack: () => cropRef.value.hidePopover()
    });
  }

  onMounted(async () => {
    onSearch();
  });

  return {
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
  };
}
