import dayjs from "dayjs";
import albumCover from "@/assets/song.jpg";
import editForm from "../form/index.vue";
import { message } from "@/utils/message";
import { addDialog, closeDialog } from "@/components/ReDialog";
import ReCropperPreview from "@/components/ReCropperPreview";
import type { PaginationProps } from "@pureadmin/table";
import { getKeyList, deviceDetection } from "@pureadmin/utils";
import {
  deleteAlbum,
  deleteAlbums,
  getAllArtists,
  getAlbumListAdmin,
  getAlbumDetailAdmin,
  addAlbum,
  updateAlbum,
  updateAlbumCover
} from "@/api/system";
import { type Ref, h, ref, toRaw, reactive, onMounted } from "vue";
import { ElMessageBox } from "element-plus";
import type { ArtistOption, FormItemProps } from "./types";

export function useAlbum(tableRef: Ref) {
  const form = reactive({
    pageNum: 1,
    pageSize: 10,
    albumName: null as string | null,
    artistId: null as number | null,
    status: null as "DRAFT" | "PUBLISHED" | null,
    // 服务端排序：sortField 由列 prop 映射（白名单校验在后端），order 为 asc/desc
    sortField: null as string | null,
    order: null as string | null
  });
  const formRef = ref();
  const dataList = ref([]);
  const artistOptions = ref<ArtistOption[]>([]);
  const loading = ref(true);
  const detailVisible = ref(false);
  const detailLoading = ref(false);
  const detailAlbum = ref<any>(null);
  const coverInfo = ref();
  const cropRef = ref();
  const selectedNum = ref(0);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 10,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    {
      label: "勾选列",
      type: "selection",
      fixed: "left",
      reserveSelection: true
    },
    {
      label: "专辑编号",
      prop: "albumId",
      width: 100,
      sortable: "custom"
    },
    {
      label: "封面",
      prop: "cover",
      width: 100,
      cellRenderer: ({ row }) => (
        <el-image
          fit="cover"
          preview-teleported={true}
          src={row.cover || albumCover}
          preview-src-list={Array.of(row.cover || albumCover)}
          class="w-[72px] h-[72px] rounded-lg align-middle"
        />
      )
    },
    {
      label: "专辑名",
      prop: "albumName",
      minWidth: 180,
      sortable: "custom" // 按拼音排序（后端 CONVERT(name USING gbk)）
    },
    {
      label: "歌手",
      prop: "artistName",
      minWidth: 150
    },
    {
      label: "发行日期",
      prop: "releaseDate",
      minWidth: 150,
      width: 150,
      sortable: "custom", // 空值始终排在最后
      formatter: ({ releaseDate }) =>
        releaseDate ? dayjs(releaseDate).format("YYYY-MM-DD") : "-"
    },
    {
      label: "歌曲数量",
      prop: "songCount",
      width: 110,
      sortable: "custom"
    },
    {
      label: "状态",
      prop: "status",
      width: 110,
      cellRenderer: ({ row }) => (
        <el-tag type={row.status === "PUBLISHED" ? "success" : "info"}>
          {row.status === "PUBLISHED" ? "已发布" : "草稿"}
        </el-tag>
      )
    },
    {
      label: "创建时间",
      prop: "createdAt",
      minWidth: 170,
      sortable: "custom",
      formatter: ({ createdAt }) =>
        createdAt ? dayjs(createdAt).format("YYYY-MM-DD HH:mm") : "-"
    },
    {
      label: "操作",
      fixed: "right",
      width: 180,
      slot: "operation"
    }
  ];

  const buttonClass = [
    "!h-[20px]",
    "reset-margin",
    "!text-gray-500",
    "dark:!text-white",
    "dark:hover:!text-primary"
  ];

  async function fetchArtists() {
    const result = await getAllArtists();
    if (result.code === 0 && Array.isArray(result.data)) {
      artistOptions.value = result.data.map(item => ({
        artistId: item.artistId,
        artistName: item.artistName
      }));
    }
  }

  async function onSearch() {
    loading.value = true;
    try {
      const result = await getAlbumListAdmin(toRaw(form));
      if (result.code === 0 && result.data?.items) {
        pagination.total = result.data.total || 0;
        const artistMap = new Map(
          artistOptions.value.map(item => [item.artistId, item.artistName])
        );
        dataList.value = result.data.items.map(item => ({
          albumId: item.albumId,
          artistId: item.artistId,
          albumName: item.albumName,
          artistName: item.artistName || artistMap.get(item.artistId) || "",
          cover: item.coverUrl,
          releaseDate: item.releaseDate,
          description: item.description,
          songCount: item.songCount || 0,
          status: item.status,
          createdAt: item.createdAt
        }));
      } else {
        dataList.value = [];
        pagination.total = 0;
      }
    } catch (error) {
      console.error("获取专辑列表失败", error);
      dataList.value = [];
      pagination.total = 0;
      message("获取专辑列表失败，请稍后重试", { type: "error" });
    } finally {
      loading.value = false;
    }
  }

  function handleSizeChange(val: number) {
    pagination.pageSize = val;
    form.pageSize = val;
    onSearch();
  }

  function handleCurrentChange(val: number) {
    pagination.currentPage = val;
    form.pageNum = val;
    onSearch();
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

  function handleSelectionChange(val) {
    selectedNum.value = val.length;
    tableRef.value.setAdaptive();
  }

  function onSelectionCancel() {
    selectedNum.value = 0;
    tableRef.value.getTableRef().clearSelection();
  }

  function resetForm(formEl) {
    if (!formEl) return;
    formEl.resetFields();
    form.pageNum = 1;
    // 排序不属于表单项，resetFields 不会清掉，手动还原为默认排序
    form.sortField = null;
    form.order = null;
    onSearch();
  }

  function handleDelete(row) {
    addDialog({
      title: "删除专辑",
      width: "460px",
      closeOnClickModal: false,
      contentRenderer: () => (
        <div class="leading-6">
          <p class="mb-2">
            {row.songCount
              ? `专辑《${row.albumName}》包含 ${row.songCount} 首歌曲，请选择删除方式：`
              : `专辑《${row.albumName}》暂无歌曲，可直接删除。`}
          </p>
          <p class="text-[13px] text-gray-500">
            · 删除专辑，歌曲保留为单曲：歌曲、音频、歌单、收藏和评论均保留；
            <br />· 删除专辑及全部歌曲：专辑内的歌曲及其音频文件将被永久删除，且不可恢复。
          </p>
        </div>
      ),
      footerButtons: [
        {
          label: "取消",
          text: true,
          bg: true,
          btnClick: ({ dialog: { options, index } }) =>
            closeDialog(options, index)
        },
        {
          label: "删除专辑，歌曲保留为单曲",
          type: "primary",
          text: true,
          bg: true,
          btnClick: ({ dialog: { options, index } }) => {
            closeDialog(options, index);
            doDeleteAlbum(row, false);
          }
        },
        {
          label: "删除专辑及全部歌曲",
          type: "danger",
          text: true,
          bg: true,
          btnClick: ({ dialog: { options, index } }) => {
            closeDialog(options, index);
            doDeleteAlbum(row, true);
          }
        }
      ]
    });
  }

  /** 执行删除；deleteSongs 为 true 时先进行二次确认，再连同专辑内所有歌曲一起删除 */
  async function doDeleteAlbum(row, deleteSongs: boolean) {
    if (deleteSongs) {
      try {
        await ElMessageBox.confirm(
          `确定要删除专辑《${row.albumName}》及其全部 ${row.songCount} 首歌曲吗？歌曲及其音频文件将被永久删除，此操作不可恢复。`,
          "二次确认",
          {
            confirmButtonText: "确认删除专辑及歌曲",
            cancelButtonText: "取消",
            type: "error",
            closeOnClickModal: false
          }
        );
      } catch {
        // 用户取消二次确认
        return;
      }
    }
    try {
      const res = await deleteAlbum(row.albumId, deleteSongs);
      if (res.code === 0) {
        message(
          deleteSongs
            ? `已删除专辑《${row.albumName}》及其 ${row.songCount} 首歌曲`
            : `已删除专辑《${row.albumName}》`,
          { type: "success" }
        );
        onSearch();
      } else {
        message(res.message || "删除专辑失败", { type: "error" });
      }
    } catch (error) {
      console.error("删除专辑失败", error);
      message("删除专辑失败，请稍后重试", { type: "error" });
    }
  }

  async function onbatchDel() {
    const selected = tableRef.value.getTableRef().getSelectionRows();
    const ids = getKeyList(selected, "albumId");
    const res = await deleteAlbums(ids);
    if (res.code === 0) {
      message(`已删除专辑编号为 ${ids} 的数据`, { type: "success" });
      tableRef.value.getTableRef().clearSelection();
      selectedNum.value = 0;
      onSearch();
    } else {
      message(res.message || "批量删除专辑失败", { type: "error" });
    }
  }

  function openDialog(row: FormItemProps) {
    addDialog({
      title: row?.albumId ? "编辑专辑" : "新建专辑",
      props: {
        formInline: {
          formTitle: row?.albumId ? "修改" : "新增",
          albumId: row?.albumId,
          artistId: row?.artistId ?? undefined,
          artistName: row?.artistName ?? "",
          albumName: row?.albumName ?? "",
          releaseDate: row?.releaseDate ?? "",
          description: row?.description ?? "",
          status: row?.status ?? "DRAFT"
        },
        artistOptions: artistOptions.value
      },
      width: "46%",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () =>
        h(editForm, {
          ref: formRef,
          formInline: null,
          artistOptions: artistOptions.value
        }),
      beforeSure: (done, { options }) => {
        const FormRef = formRef.value.getRef();
        const curData = options.props.formInline as FormItemProps;
        FormRef.validate(async valid => {
          if (!valid) return;
          const res = curData.albumId
            ? await updateAlbum(curData)
            : await addAlbum(curData);
          if (res.code === 0) {
            message(`${curData.albumId ? "已更新" : "已创建"}专辑 ${curData.albumName}`, {
              type: "success"
            });
            done();
            onSearch();
          } else {
            message(`修改专辑失败，${res.message}`, { type: "error" });
          }
        });
      }
    });
  }

  function handleUpdate(row) {
    detailVisible.value = true;
    detailAlbum.value = null;
    detailLoading.value = true;
    getAlbumDetailAdmin(row.albumId)
      .then(result => {
        if (result.code === 0) detailAlbum.value = result.data as any;
        else message(result.message || "获取专辑详情失败", { type: "error" });
      })
      .catch(() => message("获取专辑详情失败", { type: "error" }))
      .finally(() => (detailLoading.value = false));
  }

  function handleUpload(row) {
    addDialog({
      title: "裁剪、上传专辑封面",
      width: "40%",
      closeOnClickModal: false,
      fullscreen: deviceDetection(),
      contentRenderer: () =>
        h(ReCropperPreview, {
          ref: cropRef,
          uploadable: true,
          circled: false,
          imgSrc: row.cover || albumCover,
          onCropper: info => (coverInfo.value = info)
        }),
      beforeSure: async done => {
        if (!coverInfo.value?.blob) {
          message("图片裁剪失败，请重试", { type: "error" });
          return;
        }
        const formData = new FormData();
        formData.append("cover", coverInfo.value.blob);
        try {
          const res = await updateAlbumCover(row.albumId, formData);
          if (res.code === 0) {
            message("上传专辑封面成功", { type: "success" });
            done();
            onSearch();
          } else {
            message(res.message || "上传专辑封面失败", { type: "error" });
          }
        } catch (error) {
          console.error("上传专辑封面失败", error);
          message("上传专辑封面失败，请重试", { type: "error" });
        }
      },
      closeCallBack: () => cropRef.value?.hidePopover()
    });
  }

  onMounted(async () => {
    await fetchArtists();
    await onSearch();
  });

  return {
    form,
    loading,
    detailVisible,
    detailLoading,
    detailAlbum,
    columns,
    dataList,
    artistOptions,
    selectedNum,
    pagination,
    buttonClass,
    deviceDetection,
    onSearch,
    resetForm,
    onbatchDel,
    openDialog,
    openCreateDialog: () => openDialog(null as any),
    handleUpdate,
    handleDelete,
    handleUpload,
    handleSizeChange,
    handleSortChange,
    onSelectionCancel,
    handleCurrentChange,
    handleSelectionChange
  };
}
