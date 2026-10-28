import "./reset.css";
import dayjs from "dayjs";
import editForm from "../form/index.vue";
import { message } from "@/utils/message";
import userAvatar from "@/assets/user.jpg";
import { addDialog } from "@/components/ReDialog";
import type { PaginationProps } from "@pureadmin/table";
import type { FormItemProps } from "../utils/types";
import { getKeyList, deviceDetection } from "@pureadmin/utils";
import {
  getArtistList,
  addArtistWithAvatar,
  updateArtistWithAvatar,
  deleteArtist,
  deleteArtists
} from "@/api/system";
import { type Ref, h, ref, toRaw, reactive, onMounted } from "vue";

export function useArtist(tableRef: Ref) {
  const form = reactive({
    pageNum: 1,
    pageSize: 10,
    artistName: null,
    area: null,
    gender: null,
    /**
     * 状态筛选：ACTIVE-只看启用中（默认），DISABLED-只看已停用，""-全部。
     * 停用记录对业务不可见，只在追溯「合并掉的组合歌手」时才需要查看。
     */
    status: "ACTIVE",
    // 服务端排序：sortField 由列 prop 映射（白名单校验在后端），order 为 asc/desc
    sortField: null,
    order: null
  });
  const formRef = ref();
  const dataList = ref([]);
  const loading = ref(true);
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
      label: "歌手编号",
      prop: "artistId",
      width: 90,
      sortable: "custom"
    },
    {
      label: "歌手头像",
      prop: "avatar",
      cellRenderer: ({ row }) => (
        <el-image
          fit="cover"
          preview-teleported={true}
          src={row.avatar || userAvatar}
          preview-src-list={Array.of(row.avatar || userAvatar)}
          class="w-[64px] h-[64px] rounded-full align-middle"
        >
          {{
            error: () => (
              <img
                src={userAvatar}
                class="w-[64px] h-[64px] rounded-full align-middle"
              />
            )
          }}
        </el-image>
      ),
      width: 90
    },
    {
      label: "歌手",
      prop: "artistName",
      minWidth: 130,
      width: 200,
      sortable: "custom" // 按拼音排序（后端 CONVERT(name USING gbk)）
    },
    {
      label: "类型",
      prop: "gender",
      width: 50,
      className: "nowrap-col",
      cellRenderer: ({ row, props }) => (
        <el-tag
          size={props.size}
          type={
            row.gender === 0
              ? "primary"
              : row.gender === 1
                ? "danger"
                : row.gender === 2
                  ? "warning"
                  : "info"
          }
          effect="plain"
        >
          {row.gender === 0
            ? "男歌手"
            : row.gender === 1
              ? "女歌手"
              : row.gender === 2
                ? "组合/乐队"
                : "未知"}
        </el-tag>
      )
    },
    {
      label: "生日",
      minWidth: 130,
      width: 130,
      prop: "birth",
      sortable: "custom",
      formatter: ({ birth }) => (birth ? dayjs(birth).format("YYYY-MM-DD") : "")
    },
    {
      label: "状态",
      prop: "status",
      width: 90,
      className: "nowrap-col",
      cellRenderer: ({ row, props }) => (
        <el-tag
          size={props.size}
          type={row.status === "DISABLED" ? "info" : "success"}
          effect="plain"
        >
          {row.status === "DISABLED" ? "已停用" : "启用"}
        </el-tag>
      )
    },
    {
      label: "国籍",
      prop: "area",
      // 国籍是短文本：不换行 + 给足宽度，避免被挤成「中」「国」两行
      minWidth: 110,
      width: 110,
      className: "nowrap-col"
    },
    {
      label: "简介",
      prop: "introduction",
      // 不固定宽度，让简介列吸收剩余空间：宽屏时表格铺满，窄屏时先压到最小宽度再横向滚动
      minWidth: 260,
      formatter: ({ introduction }) => {
        if (!introduction) {
          return "";
        }
        if (typeof introduction !== "string") {
          return "无效数据";
        }
        const maxChars = 30; // 两行最多30个字符（列宽已收窄）
        if (introduction.length > maxChars) {
          return introduction.slice(0, maxChars) + "...";
        }
        return introduction;
      }
    },
    {
      label: "操作",
      fixed: "right",
      width: 150,
      slot: "operation"
    }
  ];
  function handleDelete(row) {
    deleteArtist(row.artistId)
      .then(res => {
        if (res.code === 0) {
          message(`您删除了歌手编号为 ${row.artistId} 的这条数据`, {
            type: "success"
          });
          onSearch();
        } else {
          message("删除歌手失败，" + (res.message ?? ""), { type: "error" });
        }
      })
      .catch(err => {
        // 以前没有 catch：401（token 失效）/403/500 会被静默吞掉，
        // 页面既不提示也不刷新，看起来就是「点了删除没反应」。
        const detail = err?.response?.data?.message ?? err?.message ?? "";
        message(`删除歌手失败${detail ? "：" + detail : ""}`, {
          type: "error"
        });
      });
  }

  function handleSizeChange(val: number) {
    // console.log(`${val} items per page`);
    pagination.pageSize = val; // 更新每页条目数
    form.pageSize = val; // 更新页码参数
    onSearch(); // 重新获取数据
  }

  function handleCurrentChange(val: number) {
    // console.log(`current page: ${val}`);
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
    // 用于多选表格，清空歌手的选择
    tableRef.value.getTableRef().clearSelection();
  }

  /** 批量删除 */
  function onbatchDel() {
    // 返回当前选中的行
    const curSelected = tableRef.value.getTableRef().getSelectionRows();
    const ids = getKeyList(curSelected, "artistId");

    deleteArtists(ids)
      .then(res => {
        if (res.code === 0) {
          message(`已删除歌手编号为 ${ids} 的数据`, {
            type: "success"
          });
          tableRef.value.getTableRef().clearSelection();
          onSearch();
        } else {
          message("删除歌手失败", { type: "error" });
        }
      })
      .catch(err => {
        console.error("删除歌手失败", err);
        message("删除歌手失败", { type: "error" });
      });
  }

  async function onSearch() {
    try {
      const result = await getArtistList(toRaw(form)); // 获取完整的返回数据

      if (result.code === 0 && result.data && result.data.items) {
        // 如果返回状态码为 0（成功），且 data 和 items 存在
        pagination.total = result.data.total; // 设置总条目数
        dataList.value = result.data.items.map(item => ({
          artistId: item.artistId,
          artistName: item.artistName,
          gender: item.gender,
          avatar: item.avatar,
          birth: item.birth,
          area: item.area,
          introduction: item.introduction,
          // 软删除标记：仅在开启「包含停用」时才会出现 DISABLED 的行
          status: item.status
        }));
      } else {
        // 如果返回状态码不为 0 或 data.items 不存在
        dataList.value = [];
        pagination.total = 0;
        message("未找到匹配的歌手信息", { type: "warning" });
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
    // 排序不属于表单项，resetFields 不会清掉，手动还原为默认排序
    form.sortField = null;
    form.order = null;
    onSearch();
  };

  function openDialog(title = "新增", row?: FormItemProps) {
    addDialog({
      title: `${title}歌手`,
      props: {
        formInline: {
          title,
          artistId: row?.artistId ?? "",
          artistName: row?.artistName ?? "",
          gender: row?.gender ?? "",
          birth: row?.birth ?? "",
          area: row?.area ?? "",
          introduction: row?.introduction ?? "",
          status: row?.status ?? "ACTIVE",
          avatarFile: null,
          // 修改模式把库里已有的头像带进来：既是预览，也是「还原」的目标
          avatarPreview: row?.avatar ?? "",
          qqSingerMid: "",
          qqAvatarSize: 500
        }
      },
      width: "46%",
      draggable: true,
      fullscreen: deviceDetection(),
      fullscreenIcon: true,
      closeOnClickModal: false,
      contentRenderer: () => h(editForm, { ref: formRef, formInline: null }),
      beforeSure: (done, { options }) => {
        const FormRef = formRef.value.getRef();
        const curData = options.props.formInline as FormItemProps;
        function chores() {
          message(`您${title}了歌手为 ${curData.artistName} 的这条数据`, {
            type: "success"
          });
          done(); // 关闭弹框
          onSearch(); // 刷新表格数据
        }
        FormRef.validate(valid => {
          if (valid) {
            // 表单规则校验通过
            if (title === "新增") {
              // 头像与歌手信息一次提交：分成两个请求时，第二步失败会留下「有歌手没头像」
              // 的半成品，而 addArtist 又不返回 id，前端没法补救。
              const {
                avatarFile,
                avatarPreview,
                qqSingerMid,
                qqAvatarSize,
                ...artistPayload
              } = curData;
              void avatarPreview;
              addArtistWithAvatar(artistPayload, avatarFile ?? undefined, {
                qqSingerMid: qqSingerMid || null,
                avatarSize: qqAvatarSize ?? null
              })
                .then(res => {
                  if (res.code === 0) {
                    chores();
                  } else {
                    message("新增歌手失败，" + res.message, { type: "error" });
                  }
                })
                .catch(err => {
                  const detail =
                    err?.response?.data?.message ?? err?.message ?? "";
                  message(`新增歌手失败${detail ? "：" + detail : ""}`, {
                    type: "error"
                  });
                });
            } else {
              // 修改也要能换头像：走 multipart 版本，一次提交文字字段 + 可选新头像。
              // 不传头像时后端保持原头像不变（空值不等于清空）。
              const {
                avatarFile,
                avatarPreview,
                qqSingerMid,
                qqAvatarSize,
                ...artistPayload
              } = curData;
              void avatarPreview;
              updateArtistWithAvatar(artistPayload, avatarFile ?? undefined, {
                qqSingerMid: qqSingerMid || null,
                avatarSize: qqAvatarSize ?? null
              })
                .then(res => {
                  if (res.code === 0) {
                    chores();
                  } else {
                    message("修改歌手失败，" + res.message, { type: "error" });
                  }
                })
                .catch(err => {
                  const detail =
                    err?.response?.data?.message ?? err?.message ?? "";
                  message(`修改歌手失败${detail ? "：" + detail : ""}`, {
                    type: "error"
                  });
                });
            }
          }
        });
      }
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
    deviceDetection,
    onSearch,
    resetForm,
    onbatchDel,
    openDialog,
    handleDelete,
    handleSizeChange,
    handleSortChange,
    onSelectionCancel,
    handleCurrentChange,
    handleSelectionChange
  };
}
