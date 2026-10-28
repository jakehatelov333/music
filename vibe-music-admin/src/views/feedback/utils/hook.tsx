import "./reset.css";
import dayjs from "dayjs";
import { message } from "@/utils/message";
import type { PaginationProps } from "@pureadmin/table";
import { deviceDetection } from "@pureadmin/utils";
import {
  getFeedbackList,
  deleteFeedback,
  deleteFeedbacks,
  markFeedbacksRead
} from "@/api/system";
import { type ElForm, ElMessageBox } from "element-plus";
import { type Ref, ref, toRaw, reactive, onMounted } from "vue";

export function useFeedback(tableRef: Ref) {
  const form = reactive({
    pageNum: 1,
    pageSize: 20,
    keyword: null,
    // -1 表示全部，0 表示未查看，1 表示已查看；默认只看未查看
    isRead: 0,
    // 服务端排序：sortField 由列 prop 映射（白名单校验在后端），order 为 asc/desc
    sortField: null,
    order: null
  });
  const dataList = ref([]);
  const loading = ref(true);
  const selectedNum = ref(0);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 20,
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
      label: "反馈编号",
      prop: "feedbackId",
      width: 95,
      className: "nowrap-col",
      sortable: "custom"
    },
    {
      label: "用户编号",
      prop: "userId",
      width: 95,
      className: "nowrap-col",
      sortable: "custom"
    },
    {
      label: "查看状态",
      prop: "isRead",
      width: 100,
      cellRenderer: ({ row }) =>
        row.isRead === 1 ? (
          <el-tag type="info" effect="light">
            已查看
          </el-tag>
        ) : (
          <el-tag type="warning" effect="light">
            未查看
          </el-tag>
        )
    },
    {
      label: "反馈内容",
      prop: "feedback",
      minWidth: 240,
      cellRenderer: ({ row }) => {
        const feedback = row.feedback;
        if (!feedback || typeof feedback !== "string") {
          return feedback === null || feedback === undefined ? "" : "无效数据";
        }

        const style = {
          "white-space": "normal",
          "word-break": "break-all"
        };

        return (
          <div style={style} title={feedback}>
            {feedback}
          </div>
        );
      }
    },
    {
      label: "反馈时间",
      width: 170,
      prop: "createTime",
      sortable: "custom",
      formatter: ({ createTime }) =>
        dayjs(createTime).format("YYYY-MM-DD HH:mm:ss")
    },
    {
      label: "已查看时间",
      width: 160,
      prop: "readTime",
      sortable: "custom",
      formatter: ({ readTime }) =>
        readTime ? dayjs(readTime).format("YYYY-MM-DD HH:mm:ss") : "-"
    },
    {
      label: "操作",
      fixed: "right",
      width: 180,
      slot: "operation"
    }
  ];

  function handleDelete(row) {
    deleteFeedback(row.feedbackId)
      .then(res => {
        if (res.code === 0) {
          message(`您删除了反馈编号为 ${row.feedbackId} 的这条数据`, {
            type: "success"
          });
          onSearch();
        } else {
          message("删除反馈失败: " + res.message, { type: "error" });
        }
      })
      .catch(err => {
        message("删除反馈失败", { type: "error" });
        console.error("删除反馈错误:", err);
      });
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
    if (tableRef) {
      selectedNum.value = val.length;
      selectedFeedbackIds.value = val.map(item => item.feedbackId);
    }
  }

  const selectedFeedbackIds = ref<number[]>([]);

  function onSelectionCancel() {
    selectedNum.value = 0;
    selectedFeedbackIds.value = [];
    tableRef.value?.getTableRef()?.clearSelection();
  }

  function onbatchDel() {
    if (selectedFeedbackIds.value.length === 0) {
      message("请先选择需要删除的反馈", { type: "warning" });
      return;
    }
    ElMessageBox.confirm(
      `确认要<strong>批量删除</strong><strong style='color:var(--el-color-primary)'> ${selectedFeedbackIds.value.length} </strong>条反馈吗?`,
      "系统提示",
      {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning",
        dangerouslyUseHTMLString: true,
        draggable: true
      }
    )
      .then(() => {
        deleteFeedbacks(selectedFeedbackIds.value)
          .then(res => {
            if (res.code === 0) {
              message(`成功删除了 ${selectedFeedbackIds.value.length} 条反馈`, {
                type: "success"
              });
              onSearch();
              onSelectionCancel();
            } else {
              message("批量删除反馈失败: " + res.message, { type: "error" });
            }
          })
          .catch(err => {
            message("批量删除反馈失败", { type: "error" });
            console.error("批量删除反馈错误:", err);
          });
      })
      .catch(() => {});
  }

  /** 单条标记已查看 */
  function handleMarkRead(row) {
    markFeedbacksRead([row.feedbackId])
      .then(res => {
        if (res.code === 0) {
          message(`反馈编号 ${row.feedbackId} 已标记为已查看`, {
            type: "success"
          });
          onSelectionCancel();
          onSearch();
        } else {
          message("标记已查看失败: " + res.message, { type: "error" });
        }
      })
      .catch(err => {
        message("标记已查看失败", { type: "error" });
        console.error("标记反馈已查看错误:", err);
      });
  }

  /** 批量标记已查看 */
  function onBatchMarkRead() {
    const ids = [...selectedFeedbackIds.value];
    if (ids.length === 0) {
      message("请先选择需要标记的反馈", { type: "warning" });
      return;
    }
    markFeedbacksRead(ids)
      .then(res => {
        if (res.code === 0) {
          message(`成功标记 ${ids.length} 条反馈为已查看`, { type: "success" });
          onSelectionCancel();
          onSearch();
        } else {
          message("批量标记已查看失败: " + res.message, { type: "error" });
        }
      })
      .catch(err => {
        message("批量标记已查看失败", { type: "error" });
        console.error("批量标记反馈已查看错误:", err);
      });
  }

  async function onSearch() {
    loading.value = true;
    const params = {
      ...toRaw(form),
      isRead: form.isRead < 0 ? null : form.isRead
    };
    if (params.keyword === "" || params.keyword == null) {
      params.keyword = null;
    }

    try {
      const { data } = await getFeedbackList(params);
      dataList.value = data.items;
      pagination.total = data.total;
    } catch (e) {
      console.error("获取反馈列表错误:", e);
      message("获取反馈列表失败", { type: "error" });
      dataList.value = [];
      pagination.total = 0;
    } finally {
      setTimeout(() => {
        loading.value = false;
      }, 500);
    }
  }

  const resetForm = (formEl: InstanceType<typeof ElForm> | undefined) => {
    if (!formEl) return;
    formEl.resetFields();
    form.keyword = null;
    form.isRead = 0;
    // 排序不属于表单项，resetFields 不会清掉，手动还原为默认排序
    form.sortField = null;
    form.order = null;
    onSearch();
  };

  onMounted(() => {
    onSearch();
  });

  return {
    form,
    loading,
    columns,
    dataList,
    selectedNum,
    pagination,
    onSearch,
    resetForm,
    onbatchDel,
    onBatchMarkRead,
    handleMarkRead,
    handleDelete,
    handleSizeChange,
    handleSortChange,
    handleCurrentChange,
    handleSelectionChange,
    onSelectionCancel,
    deviceDetection
  };
}
